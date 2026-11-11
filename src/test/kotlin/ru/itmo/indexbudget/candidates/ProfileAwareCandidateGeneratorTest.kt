package ru.itmo.indexbudget.candidates

import java.time.Duration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest.dynamicTest
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.CandidateGenerationFixtures
import ru.itmo.indexbudget.fixtures.withSqlSemantics
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer

class ProfileAwareCandidateGeneratorTest {
    private val table = TableRef("public", "records")
    private val names = (1..100).map { "c$it" }
    private val catalog = CatalogSnapshot(listOf(CatalogTable(table, names.map {
        CatalogColumn(it, "int4", defaultOperatorClass = "pg_catalog.int4_ops")
    })))

    @Test
    fun `one wide query retains eight singles and eight pairs with honest three stage counts`() {
        val sql = "SELECT * FROM public.records WHERE " + names.joinToString(" AND ") { "$it = 1" }
        val analyzer = JSqlParserSqlAnalyzer()
        val input = workload(listOf(query("wide", names))).input.copy(queries = listOf(QueryInput("wide", sql)))
        val context = RunContext()
        val resolved = analyzer.resolve(analyzer.parse(input, context), catalog.withSqlSemantics(), context)
        val result = ProfileAwareCandidateGenerator().generate(resolved, catalog, context)
        assertEquals(sql, resolved.queries.single().sql)
        assertEquals(100, resolved.queries.single().predicates.size)
        assertEquals(8, result.candidates.count { it.definition.keys.size == 1 })
        assertEquals(8, result.candidates.count { it.definition.keys.size == 2 })
        assertEquals(List(8) { listOf(1, 2) }.flatten(), result.candidates.map { it.definition.keys.size })
        val stats = result.diagnostics.localScopes.single()
        assertEquals(listOf(100, 16, 256, 256, 256, 32), listOf(stats.discoveredColumns, stats.retainedColumns,
            stats.enumeratedDefinitions, stats.deduplicatedDefinitions, stats.candidatesAfterExistingDeduplication,
            stats.retainedDefinitions))
        assertEquals(84, stats.excludedColumns.size)
        assertEquals(32, result.diagnostics.candidatesBeforeGlobalDeduplication)
        assertEquals(32, result.diagnostics.candidatesAfterGlobalDeduplication)
        assertEquals(16, result.diagnostics.shortlistedCandidates)
        assertNull(result.diagnostics.ungeneratedDefinitions)
        assertEquals(setOf(WarningCode.COLUMN_SHORTLIST_TRUNCATED, WarningCode.LOCAL_CANDIDATE_LIMIT,
            WarningCode.GLOBAL_CANDIDATE_LIMIT), result.diagnostics.warnings.map { it.code }.toSet())
        val local = result.diagnostics.decisions.single { it.reason == CandidateReason.LOCAL_CANDIDATE_LIMIT }
        assertEquals("wide", local.queryId)
        assertEquals(table, local.table)
        assertEquals("256", local.details["candidates_before"])
        assertEquals("32", local.details["candidates_after"])
        assertEquals(0, result.diagnostics.decisions.count { it.reason == CandidateReason.GLOBAL_CANDIDATE_LIMIT })
        assertEquals(16, result.reserve.size)
        assertEquals(16, result.diagnostics.initialShortlistedCandidates)
        assertEquals(16, result.diagnostics.reserveCandidates)
        val pool = result.candidates + result.reserve
        assertEquals(List(16) { listOf(1, 2) }.flatten(), pool.map { it.definition.keys.size })
        assertEquals(pool.size, pool.map { it.id }.toSet().size)
        val singles = pool.filter { it.definition.keys.size == 1 }.sortedBy { it.id }
        val pairs = pool.filter { it.definition.keys.size == 2 }.sortedBy { it.id }
        assertEquals(singles.take(8).zip(pairs.take(8)).flatMap { listOf(it.first, it.second) }, result.candidates)
        assertEquals("true", result.diagnostics.warnings.single { it.code == WarningCode.GLOBAL_CANDIDATE_LIMIT }
            .details["refill_after_budget_filter"])
        assertUnknownEstimates(result)
        assertEquals(0, context.statistics().explainAttempts)
    }

    @Test
    fun `rare profile gets independent seats and profile weight is ignored`() {
        val common = query("common", names.take(16))
        val rare = query("rare", names.drop(16).take(16))
        val profiles = listOf(
            Profile("a-rare", 0.000001, mapOf("rare" to 0.001, "common" to 0.0)),
            Profile("z-common", 1e12, mapOf("rare" to 0.0, "common" to 1e12)),
        )
        val input = workload(listOf(common, rare), profiles)
        val result = generate(input)
        assertEquals(List(8) { listOf("rare", "common") }.flatten(),
            result.candidates.map { it.sourceQueryIds.single() })
        assertEquals(8, result.candidates.count { it.definition.keys.size == 1 })
        assertEquals(48, result.reserve.size)
        assertEquals(24, result.reserve.count { it.sourceQueryIds == setOf("rare") })
        assertEquals(24, result.reserve.count { it.sourceQueryIds == setOf("common") })
        for (id in listOf("rare", "common")) {
            val fromProfile = (result.candidates + result.reserve).filter { id in it.sourceQueryIds }
            assertEquals(List(16) { listOf(1, 2) }.flatten(), fromProfile.map { it.definition.keys.size })
        }
        val permuted = input.copy(input = input.input.copy(profiles = profiles.reversed().map {
            it.copy(weight = 42.0, callsPerHour = it.callsPerHour.entries.reversed().associate { it.toPair() })
        }), queries = input.queries.reversed())
        assertEquals(result, generate(permuted, catalog.copy(tables = catalog.tables.map { it.copy(columns = it.columns.reversed()) })))
    }

    @Test
    fun `bounded reserve continues three profile turns and equal score ordering deterministically`() {
        val tables = listOf("busy", "middle", "rare").map { TableRef("public", it) }
        val queries = tables.mapIndexed { i, table ->
            val columns = names.take(16).map { ColumnRef(table, it) }
            ResolvedQuery("q$i", "SELECT * FROM public.${table.name} WHERE " +
                columns.joinToString(" AND ") { "${it.name} = 1" }, setOf(table), columns.toSet(),
                columns.map { ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE) },
                emptyList(), selectsAllColumns = true)
        }
        val profiles = listOf(
            Profile("a_busy", 1e9, mapOf("q0" to 1e9, "q1" to 0.0, "q2" to 0.0)),
            Profile("b_middle", 1.0, mapOf("q0" to 0.0, "q1" to 7.0, "q2" to 0.0)),
            Profile("z_rare", 1e-9, mapOf("q0" to 0.0, "q1" to 0.0, "q2" to 1.0)),
        )
        val workload = workload(queries, profiles)
        val catalog = CatalogSnapshot(tables.map { table ->
            CatalogTable(table, this.catalog.tables.single().columns.take(16))
        })

        val result = generate(workload, catalog)
        val pool = result.candidates + result.reserve
        assertEquals(96, result.diagnostics.candidatesAfterGlobalDeduplication)
        assertEquals(16, result.candidates.size)
        assertEquals(48, result.reserve.size)
        assertEquals(64, pool.map { it.id }.toSet().size)
        assertEquals(listOf("q0", "q1", "q2"), pool.take(3).map { it.sourceQueryIds.single() })
        assertEquals(listOf(1, 1, 1, 2, 2, 2), pool.take(6).map { it.definition.keys.size })
        for (query in queries) {
            assertEquals(16, result.reserve.count { query.id in it.sourceQueryIds })
            val fromProfile = pool.filter { query.id in it.sourceQueryIds }
            assertEquals(List(fromProfile.size) { if (it % 2 == 0) 1 else 2 },
                fromProfile.map { it.definition.keys.size })
            for (arity in 1..2) {
                val ids = fromProfile.filter { it.definition.keys.size == arity }.map { it.id }
                assertEquals(ids.sorted(), ids)
            }
        }
        val excluded = result.diagnostics.decisions.filter { it.reason == CandidateReason.GLOBAL_CANDIDATE_LIMIT }
        assertEquals(32, excluded.size)
        assertTrue(excluded.none { decision -> pool.any { it.id == decision.candidateId } })
        val permuted = workload.copy(input = workload.input.copy(profiles = profiles.reversed()),
            queries = queries.reversed())
        val permutedCatalog = catalog.copy(tables = catalog.tables.reversed().map {
            it.copy(columns = it.columns.reversed())
        })
        assertEquals(result, generate(permuted, permutedCatalog))
        assertUnknownEstimates(result)
    }

    @Test
    fun `shared candidates skip selected heads without losing successful alternation`() {
        val q1 = query("q1", listOf("c1", "c2"))
        val q2 = query("q2", listOf("c1", "c3"))
        val profiles = listOf(Profile("a", 1.0, mapOf("q1" to 1.0, "q2" to 0.0)),
            Profile("b", 1.0, mapOf("q1" to 1.0, "q2" to 0.0)),
            Profile("c", 1.0, mapOf("q1" to 0.0, "q2" to 1.0)))
        val result = generate(workload(listOf(q1, q2), profiles))
        assertEquals(7, result.candidates.size)
        assertEquals(7, result.candidates.map { it.id }.toSet().size)
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 2), result.candidates.map { it.definition.keys.size })
        assertEquals(setOf("q1", "q2"), result.candidates.single {
            it.definition.keys.map { it.column.name } == listOf("c1")
        }.sourceQueryIds)
        assertTrue(result.diagnostics.warnings.isEmpty())
    }

    @Test
    fun `pair only and single only queues are exhausted and zero rated candidates are not selected`() {
        val input = workload(listOf(query("q1", listOf("c1", "c2")), query("q0", listOf("c3"))),
            listOf(Profile("p", 1.0, mapOf("q1" to 1.0, "q0" to 0.0))))
        val pairs = listOf(variant("c1", "c2"), variant("c2", "c1"))
        val result = withScopes(input, listOf(scope("q1", pairs), scope("q0", listOf(variant("c3", queryId = "q0")))))
        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.all { it.definition.keys.size == 2 && it.sourceQueryIds == setOf("q1") })
        val singles = withScopes(input, listOf(scope("q1", listOf(variant("c1"), variant("c2")))))
        assertEquals(2, singles.candidates.size)
        assertTrue(singles.candidates.all { it.definition.keys.size == 1 })
    }

    @Test
    fun `canonical dedup and exact existing removal precede local limit and keep singles`() {
        val singles = names.take(16).map { variant(it) }
        val pairs = names.take(16).flatMap { a -> names.take(16).filter { it != a }.map { b -> variant(a, b) } }
        val existingKey = IndexKey(ColumnRef(table, "c1"), operatorClass = "pg_catalog.int4_ops")
        val withExisting = catalog.copy(tables = catalog.tables.map { it.copy(existingIndexes = listOf(
            ExistingIndex("already_c1", table, method = "btree", keys = listOf(existingKey)),
        )) })
        val input = workload(listOf(query("q1", names.take(16))))
        val result = withScopes(input, listOf(scope("q1", (singles + pairs).flatMap { listOf(it, it) })), withExisting)
        val stats = result.diagnostics.localScopes.single()
        assertEquals(512, stats.enumeratedDefinitions)
        assertEquals(256, stats.deduplicatedDefinitions)
        assertEquals(255, stats.candidatesAfterExistingDeduplication)
        assertEquals(32, stats.retainedDefinitions)
        assertEquals(8, result.candidates.count { it.definition.keys.size == 1 })
        assertFalse(result.candidates.any { it.definition.keys.map { it.column.name } == listOf("c1") })
        assertEquals(1, result.diagnostics.decisions.count { it.reason == CandidateReason.EXACT_EXISTING_DUPLICATE })
        assertUnknownEstimates(result)
    }

    @Test
    fun `local pair priority beats IDs and uses best reason when groups overlap`() {
        val input = workload(listOf(query("q1", names.take(16))))
        val allPairs = names.take(16).flatMap { a -> names.take(16).filter { it != a }.map { b -> variant(a, b) } }
        val byId = allPairs.sortedBy { canonicalId(it) }
        // Deliberately give the largest IDs better groups, so an ID-only sort fails.
        val priorities = listOf(GenerationReason.ORDER_BY_PAIR, GenerationReason.EQUALITY_AND_ORDER,
            GenerationReason.EQUALITY_AND_RANGE)
        val promoted = byId.takeLast(6).mapIndexed { i, candidate -> candidate.copy(
            generationReasons = setOf(GenerationReason.TWO_EQUALITIES, priorities[i / 2])) }
        val result = withScopes(input, listOf(scope("q1", byId.dropLast(6) + promoted)))
        val first = result.candidates.take(6)
        assertEquals(listOf(0, 0, 1, 1, 2, 2), first.map { it.bestPairPriority() })
        for (group in first.chunked(2)) assertEquals(group.map { it.id }.sorted(), group.map { it.id })
        assertEquals(32, result.diagnostics.candidatesAfterGlobalDeduplication)
        assertEquals(16, result.candidates.size)
    }

    @Test
    fun `global rating sums distinct query IDs rather than counting repeated emissions`() {
        val input = workload(listOf(query("q1", listOf("c1", "c2")), query("q2", listOf("c2"))),
            listOf(Profile("p", 1.0, mapOf("q1" to 4.0, "q2" to 6.0))))
        val result = withScopes(input, listOf(
            scope("q1", List(30) { variant("c1") } + variant("c2")),
            scope("q2", listOf(variant("c2", queryId = "q2"))),
        ))
        assertEquals(listOf("c2", "c1"), result.candidates.map { it.definition.keys.single().column.name })
        assertEquals(setOf("q1", "q2"), result.candidates.first().sourceQueryIds)
        assertEquals(3, result.diagnostics.candidatesBeforeGlobalDeduplication)
        assertEquals(2, result.diagnostics.candidatesAfterGlobalDeduplication)
        assertTrue(result.diagnostics.warnings.isEmpty())
    }

    @Test
    fun `sixty wide scopes keep at most 1920 local entries without retaining raw scopes`() {
        val tables = (1..3).map { TableRef("public", "t$it") }
        val queries = (1..20).map { number ->
            val columns = tables.flatMap { t -> names.take(80).map { ColumnRef(t, it) } }
            ResolvedQuery("q$number", "source SQL $number", tables.toSet(), columns.toSet(), columns.map {
                ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE)
            }, emptyList())
        }
        val catalog = CatalogSnapshot(tables.map { CatalogTable(it, this.catalog.tables.single().columns) })
        val result = generate(workload(queries), catalog)
        assertEquals(60, result.diagnostics.localScopes.size)
        assertTrue(result.diagnostics.localScopes.all {
            it.discoveredColumns == 80 && it.retainedColumns == 16 && it.enumeratedDefinitions == 256 && it.retainedDefinitions == 32
        })
        assertEquals(1920, result.diagnostics.candidatesBeforeGlobalDeduplication)
        assertEquals(96, result.diagnostics.candidatesAfterGlobalDeduplication)
        assertEquals(16, result.candidates.size)
        assertEquals(48, result.reserve.size)
        val pool = result.candidates + result.reserve
        assertEquals(64, pool.map { it.id }.toSet().size)
        assertTrue(pool.all { it.sourceQueryIds.size == 20 })
        val excluded = result.diagnostics.decisions.filter { it.reason == CandidateReason.GLOBAL_CANDIDATE_LIMIT }
        assertEquals(32, excluded.size)
        assertTrue(excluded.none { decision -> pool.any { it.id == decision.candidateId } })

        val scenario = CandidateGenerationFixtures.twoEqualities()
        val streamed = ProfileAwareCandidateGenerator(CandidateEnumerator { w, c, consume ->
            BtreeCandidateEnumerator().forEachScope(w, c) { scope ->
                var active = true
                val transient = object : AbstractList<CandidateVariant>() {
                    override val size get() = scope.variants.size
                    override fun get(index: Int): CandidateVariant {
                        check(active) { "Raw generation scope was retained beyond its callback" }
                        return scope.variants[index]
                    }
                }
                consume(scope.copy(variants = transient))
                active = false
            }
        }).generate(scenario.workload, scenario.catalog, RunContext())
        assertEquals(4, streamed.candidates.size)
    }

    @Test
    fun `deadline inside final selection is not swallowed or returned as partial batch`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(100)), clock = NanoClock { now })
        val input = workload(listOf(query("q1", listOf("c1"))))
        val frequencies = object : AbstractMap<String, Double>() {
            override val entries = mapOf("q1" to 1.0).entries
            override fun get(key: String): Double? { now = 101; return 1.0 }
        }
        val timed = input.copy(input = input.input.copy(profiles = listOf(Profile("p", 1.0, frequencies))))
        val generator = ProfileAwareCandidateGenerator(CandidateEnumerator { _, _, consume -> consume(scope("q1", listOf(variant("c1")))) })
        val error = assertThrows(AnalysisException::class.java) { generator.generate(timed, catalog, context) }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
        val cancelled = RunContext().apply { cancel() }
        assertEquals(ErrorCode.CANCELLED, assertThrows(AnalysisException::class.java) {
            generator.generate(input, catalog, cancelled)
        }.error.code)
    }

    @Test
    fun `deadline between scopes prevents consuming another scope`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(100)), clock = NanoClock { now })
        val generator = ProfileAwareCandidateGenerator(CandidateEnumerator { _, _, consume ->
            consume(scope("q1", listOf(variant("c1"))))
            now = 101
            consume(scope("q2", listOf(variant("c2", queryId = "q2"))))
            fail<Unit>("Must stop in the next scope")
        })
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AnalysisException::class.java) {
            generator.generate(workload(listOf(query("q1", listOf("c1")), query("q2", listOf("c2")))), catalog, context)
        }.error.code)
    }

    @TestFactory
    fun `all shared scenarios pass through the complete generator`() = CandidateGenerationFixtures.all().map { scenario ->
        dynamicTest(scenario.name) {
            val result = generate(scenario.workload, scenario.catalog)
            assertTrue(result.candidates.size <= 16)
            assertTrue(result.reserve.size <= 48)
            assertTrue(result.diagnostics.localScopes.all { it.retainedDefinitions <= 32 && it.retainedColumns <= 16 })
            assertUnknownEstimates(result)
        }
    }

    @Test
    fun `global cap starts above sixteen and ties of singles use stable IDs`() {
        val input = workload(listOf(query("q1", names.take(16)), query("q2", listOf("c17"))))
        val first = scope("q1", names.take(16).reversed().map { variant(it) })
        val atLimit = withScopes(input, listOf(first))
        assertEquals(16, atLimit.candidates.size)
        assertEquals(atLimit.candidates.map { it.id }.sorted(), atLimit.candidates.map { it.id })
        assertTrue(atLimit.diagnostics.warnings.isEmpty())
        val above = withScopes(input, listOf(first, scope("q2", listOf(variant("c17", queryId = "q2")))))
        assertEquals(17, above.diagnostics.candidatesAfterGlobalDeduplication)
        assertEquals(16, above.candidates.size)
        assertEquals(0, above.diagnostics.decisions.count { it.reason == CandidateReason.GLOBAL_CANDIDATE_LIMIT })
        assertEquals(1, above.reserve.size)
        assertEquals(17, (above.candidates + above.reserve).map { it.id }.toSet().size)
    }

    @Test
    fun `global merge preserves best pair priority from another query`() {
        val input = workload(listOf(query("q1", listOf("c1", "c2")), query("q2", listOf("c1", "c2"))))
        val pairs = listOf(variant("c1", "c2"), variant("c2", "c1")).sortedBy { canonicalId(it) }
        val best = pairs.last().copy(sourceQueryIds = setOf("q2"), generationReasons = setOf(GenerationReason.ORDER_BY_PAIR))
        val result = withScopes(input, listOf(
            scope("q1", pairs),
            scope("q2", listOf(pairs.first().copy(sourceQueryIds = setOf("q2")), best, best)),
        ))
        assertEquals(best.definition.keys.map { it.column.name }, result.candidates.first().definition.keys.map { it.column.name })
        assertEquals(setOf("q1", "q2"), result.candidates.first().sourceQueryIds)
        assertEquals(setOf(GenerationReason.TWO_EQUALITIES, GenerationReason.ORDER_BY_PAIR), result.candidates.first().generationReasons)
    }

    @TestFactory
    fun `overflow and underflow in normalized profile frequencies are explicit numeric errors`() = listOf(
        "overflow" to mapOf("q1" to Double.MAX_VALUE, "q2" to Double.MAX_VALUE),
        "underflow" to mapOf("q1" to Double.MIN_VALUE, "q2" to Double.MAX_VALUE),
    ).map { (label, frequencies) -> dynamicTest(label) {
        val input = workload(listOf(query("q1", listOf("c1")), query("q2", listOf("c2"))),
            listOf(Profile("numeric", 1.0, frequencies)))
        val error = assertThrows(AnalysisException::class.java) {
            withScopes(input, listOf(scope("q1", listOf(variant("c1")))))
        }.error
        assertEquals(ErrorCode.NUMERIC_ERROR, error.code)
        assertEquals("numeric", error.profileId)
    } }

    private fun generate(input: ResolvedWorkload, catalog: CatalogSnapshot = this.catalog) =
        ProfileAwareCandidateGenerator().generate(input, catalog, RunContext())

    private fun withScopes(input: ResolvedWorkload, scopes: List<CandidateScope>, catalog: CatalogSnapshot = this.catalog) =
        ProfileAwareCandidateGenerator(CandidateEnumerator { _, context, consume -> scopes.forEach { context.checkDeadline(); consume(it) } })
            .generate(input, catalog, RunContext())

    private fun scope(id: String, variants: List<CandidateVariant>) = CandidateScope(id, table, 16,
        names.take(16).map { ColumnRef(table, it) }, emptyList(), variants, emptyList(), emptyList())

    private fun variant(first: String, second: String? = null, queryId: String = "q1") = CandidateVariant(
        IndexDefinition(table, listOfNotNull(first, second).map { IndexKey(ColumnRef(table, it)) }), setOf(queryId),
        setOf(if (second == null) GenerationReason.SINGLE_COLUMN else GenerationReason.TWO_EQUALITIES))

    private fun canonicalId(variant: CandidateVariant): String = CanonicalIndex.id(
        BtreeCandidateCanonicalizer(catalog, RunContext()).canonicalize(variant.definition))

    private fun query(id: String, columns: List<String>): ResolvedQuery {
        val refs = columns.map { ColumnRef(table, it) }
        return ResolvedQuery(id, "SELECT * FROM public.records WHERE " + columns.joinToString(" AND ") { "$it = 1" },
            setOf(table), refs.toSet(), refs.map { ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE) }, emptyList())
    }

    private fun workload(queries: List<ResolvedQuery>, profiles: List<Profile> = listOf(
        Profile("p", 1.0, queries.associate { it.id to 1.0 }),
    )) = CandidateGenerationFixtures.workload(queries, profiles)

    private fun assertUnknownEstimates(result: CandidateBatch) = (result.candidates + result.reserve).forEach {
        assertNull(it.estimatedBytes)
        assertNull(it.budgetedBytes)
        assertNull(it.writeUnitsPerHour)
        assertNotNull(it.ddl)
    }
}
