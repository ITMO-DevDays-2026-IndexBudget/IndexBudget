package ru.itmo.indexbudget.candidates

import java.time.Duration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.CandidateGenerationContract
import ru.itmo.indexbudget.fixtures.CandidateGenerationFixtures
import ru.itmo.indexbudget.fixtures.ContractFixtures

class BtreeCandidateEnumeratorTest {
    private val enumerator = BtreeCandidateEnumerator()

    @TestFactory
    fun `shared SQL and candidate contract scenarios`() = CandidateGenerationFixtures.all().map { scenario ->
        dynamicTest(scenario.name) {
            CandidateGenerationContract.assertScopes(scenario, collect(scenario.workload))
        }
    }

    @Test
    fun `wide SQL and resolved conditions remain intact`() {
        val workload = CandidateGenerationFixtures.wideAllFilters().workload
        val originalSql = workload.queries.single().sql
        val originalPredicates = workload.queries.single().predicates.toList()
        val result = collect(workload).single()

        assertEquals(16, result.retainedColumns.size)
        assertEquals(84, result.excludedColumns.size)
        assertEquals(originalSql, workload.queries.single().sql)
        assertEquals(originalSql, workload.input.queries.single().sql)
        assertEquals(originalPredicates, workload.queries.single().predicates)
        assertEquals(100, workload.queries.single().predicates.size)
    }

    @Test
    fun `adding projected or unreferenced catalog columns does not change candidates`() {
        val scenario = CandidateGenerationFixtures.wideTwoFeatures()
        val query = scenario.workload.queries.single()
        val narrow = query.copy(referencedColumns = query.predicates.map { it.leftColumn }.toSet() + query.orderBy.map { it.column })
        val wide = query.copy(projectedColumns = query.referencedColumns)

        assertEquals(collect(scenario.workload.copy(queries = listOf(narrow))), collect(scenario.workload.copy(queries = listOf(wide))))
        assertEquals(100, scenario.catalog.tables.single().columns.size)
    }

    @Test
    fun `order by columns precede support even on a wide query`() {
        val scenario = CandidateGenerationFixtures.wideAllFilters()
        val query = scenario.workload.queries.single()
        val table = query.tables.single()
        val order = listOf(OrderByColumn(ColumnRef(table, "c99"), SortDirection.DESC), OrderByColumn(ColumnRef(table, "c98")))
        val sorted = query.copy(sql = query.sql + " ORDER BY c99 DESC, c98 ASC", orderBy = order)
        val scope = collect(CandidateGenerationFixtures.workload(listOf(sorted))).single()

        assertEquals(listOf("c99", "c98"), scope.retainedColumns.take(2).map { it.name })
        assertEquals(16, scope.retainedColumns.size)
        val pair = scope.variants.single { it.definition.keys.map { key -> key.column.name } == listOf("c99", "c98") }
        assertTrue(GenerationReason.ORDER_BY_PAIR in pair.generationReasons)
        assertEquals(listOf(SortDirection.DESC, SortDirection.ASC), pair.definition.keys.map { it.direction })
        assertEquals(listOf(NullsOrder.FIRST, NullsOrder.LAST), pair.definition.keys.map { it.nulls })
    }

    @Test
    fun `equality precedes range only when support ties`() {
        val base = CandidateGenerationFixtures.wideAllFilters().workload.queries.single()
        val predicates = base.predicates.map {
            if (it.leftColumn.name == "c99") it else it.copy(operator = ComparisonOperator.LESS_THAN)
        }
        val query = base.copy(predicates = predicates)
        val scope = collect(CandidateGenerationFixtures.workload(listOf(query))).single()

        assertEquals("c99", scope.retainedColumns.first().name)
        assertEquals(listOf("c1", "c10", "c100"), scope.retainedColumns.drop(1).take(3).map { it.name })
        assertEquals(31, scope.enumeratedDefinitions) // 16 singles and equality before each of 15 ranges.
    }

    @Test
    fun `range with higher support precedes equality in the column shortlist`() {
        val table = ContractFixtures.wideTable
        val equalityColumns = (1..16).map { ColumnRef(table, "c$it") }
        val rangeColumn = ColumnRef(table, "c17")
        val rangePredicate = ResolvedPredicate(rangeColumn, ComparisonOperator.GREATER_THAN, PredicateSource.WHERE)
        val wideQuery = ResolvedQuery(
            id = "wide",
            sql = "SELECT * FROM public.wide_records WHERE " +
                equalityColumns.joinToString(" AND ") { "${it.name} = 1" } + " AND c17 > 0",
            tables = setOf(table),
            referencedColumns = equalityColumns.toSet() + rangeColumn,
            predicates = equalityColumns.map {
                ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE)
            } + rangePredicate,
            orderBy = emptyList(),
            selectsAllColumns = true,
        )
        val frequentQuery = wideQuery.copy(
            id = "frequent",
            sql = "SELECT * FROM public.wide_records WHERE c17 > 0",
            referencedColumns = setOf(rangeColumn),
            predicates = listOf(rangePredicate),
        )
        val profiles = listOf(Profile("normal", 1.0, mapOf("wide" to 1.0, "frequent" to 99.0)))
        val workload = CandidateGenerationFixtures.workload(listOf(wideQuery, frequentQuery), profiles)

        val scope = collect(workload).single { it.queryId == "wide" }

        assertEquals(rangeColumn, scope.retainedColumns.first())
        assertEquals(16, scope.retainedColumns.size)
        assertEquals(listOf(ColumnRef(table, "c9")), scope.excludedColumns)
    }

    @Test
    fun `rare profile participates regardless of absolute traffic or weight`() {
        val workload = CandidateGenerationFixtures.profileSupport().workload
        val scope = collect(workload).single { it.queryId == "wide" }
        assertEquals(listOf("c17", "c18"), scope.retainedColumns.take(2).map { it.name })
        assertEquals(setOf("c8", "c9"), scope.excludedColumns.map { it.name }.toSet())

        val rescaled = workload.copy(input = workload.input.copy(profiles = workload.input.profiles.map { profile ->
            profile.copy(weight = if (profile.id == "campaign") 1e-20 else 1e20,
                callsPerHour = profile.callsPerHour.mapValues { it.value * 1024.0 })
        }))
        assertEquals(collect(workload), collect(rescaled))
    }

    @Test
    fun `support uses maximum across profiles rather than their sum`() {
        val workload = CandidateGenerationFixtures.profileSupport().workload
        val frequencies = listOf(
            Profile("a", 1.0, mapOf("wide" to 1.0, "common" to 8.0, "rare" to 1.0)),
            Profile("b", 1.0, mapOf("wide" to 1.0, "common" to 8.0, "rare" to 1.0)),
            Profile("c", 1.0, mapOf("wide" to 1.0, "common" to 0.0, "rare" to 9.0)),
        )
        // c17 supports: .9/.9/.1 (sum 1.9); c18: .2/.2/1.0 (sum 1.4). Only max picks c18.
        val scope = collect(workload.copy(input = workload.input.copy(profiles = frequencies))).single { it.queryId == "wide" }
        assertEquals("c18", scope.retainedColumns.first().name)
    }

    @Test
    fun `support counts a query only once despite repeated predicates`() {
        val workload = CandidateGenerationFixtures.profileSupport().workload
        val repeated = workload.copy(queries = workload.queries.map { query ->
            query.copy(predicates = query.predicates + List(100) { query.predicates.first() })
        })
        assertEquals(collect(workload), collect(repeated))
    }

    @Test
    fun `query profile table and predicate iteration order does not change results`() {
        val workload = CandidateGenerationFixtures.profileSupport().workload
        val reversedQueries = workload.queries.reversed().map { query ->
            query.copy(predicates = query.predicates.reversed())
        }
        val reversedProfiles = workload.input.profiles.reversed().map { profile ->
            val reversedCallsPerHour = profile.callsPerHour.entries.reversed().associate { it.toPair() }
            profile.copy(callsPerHour = reversedCallsPerHour)
        }
        val reversedInput = workload.input.copy(
            queries = workload.input.queries.reversed(),
            profiles = reversedProfiles,
        )
        val shuffled = workload.copy(
            queries = reversedQueries,
            input = reversedInput,
        )
        assertEquals(collect(workload), collect(shuffled))

        val join = CandidateGenerationFixtures.join().workload
        val queriesWithReversedTables = join.queries.map { query ->
            val reversedTables = query.tables.reversed().toSet()
            query.copy(tables = reversedTables)
        }
        val shuffledJoin = join.copy(queries = queriesWithReversedTables)
        assertEquals(collect(join), collect(shuffledJoin))
    }

    @Test
    fun `no pairs combine conditions from different queries`() {
        val a = CandidateGenerationFixtures.twoEqualities().workload.queries.single()
        val queries = a.predicates.mapIndexed { index, predicate ->
            a.copy(id = "q$index", predicates = listOf(predicate), referencedColumns = setOf(predicate.leftColumn))
        }
        val scopes = collect(CandidateGenerationFixtures.workload(queries))
        assertEquals(listOf(1, 1), scopes.map { it.enumeratedDefinitions })
        assertTrue(scopes.flatMap { it.variants }.all { it.definition.keys.size == 1 })
    }

    @Test
    fun `overlapping sort directions enumerate unique variants with a four direction cap`() {
        val base = CandidateGenerationFixtures.twoEqualities().workload.queries.single()
        val a = base.predicates.first().leftColumn
        val query = base.copy(
            sql = base.sql + " ORDER BY a ASC, a DESC",
            orderBy = listOf(OrderByColumn(a), OrderByColumn(a, SortDirection.DESC)),
        )
        val scope = collect(CandidateGenerationFixtures.workload(listOf(query))).single()
        assertEquals(6, scope.enumeratedDefinitions) // two singles, two directions for each ordered pair.
        assertEquals(6, scope.variants.map { it.definition }.distinct().size)
        assertTrue(scope.variants.filter { it.definition.keys.size == 2 }
            .groupBy { it.definition.keys.map { key -> key.column } }.values.all { it.size <= 4 })
        assertTrue(scope.variants.none { GenerationReason.ORDER_BY_PAIR in it.generationReasons })
    }

    @Test
    fun `all sixty scopes stay bounded and are delivered synchronously`() {
        val workload = maximumWorkload()
        var count = 0
        var total = 0
        var lastQuery = ""
        enumerator.forEachScope(workload, RunContext()) { scope ->
            count++
            total += scope.enumeratedDefinitions
            assertEquals(16, scope.retainedColumns.size)
            assertEquals(256, scope.enumeratedDefinitions)
            assertTrue(scope.queryId >= lastQuery)
            lastQuery = scope.queryId
        }
        assertEquals(60, count)
        assertEquals(15_360, total)
        assertTrue(total <= 58_560)
    }

    @Test
    fun `exceeding workload bounds fails before delivering scopes`() {
        val base = maximumWorkload()
        val tooManyQueries = base.copy(queries = base.queries + base.queries.first().copy(id = "extra"))
        val tooManyTables = base.copy(queries = listOf(base.queries.first().copy(tables = (1..4).map { TableRef("public", "t$it") }.toSet())))
        for (workload in listOf(tooManyQueries, tooManyTables)) {
            assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, assertThrows(AnalysisException::class.java) {
                enumerator.forEachScope(workload, RunContext()) { fail<Unit>("No scope should be delivered") }
            }.error.code)
        }
    }

    @Test
    fun `expired deadline prevents any delivery`() {
        var now = 0L
        val context = RunContext(clock = NanoClock { now })
        now = Duration.ofSeconds(90).toNanos()
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AnalysisException::class.java) {
            enumerator.forEachScope(maximumWorkload(), context) { fail<Unit>("Expired scope delivered") }
        }.error.code)
    }

    @Test
    fun `deadline is checked during pair enumeration`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(150)), clock = NanoClock { now++ })
        val base = CandidateGenerationFixtures.wideAllFilters().workload.queries.single()
        val query = base.copy(predicates = base.predicates.take(16))
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AnalysisException::class.java) {
            enumerator.forEachScope(CandidateGenerationFixtures.workload(listOf(query)), context) { fail<Unit>("Unfinished scope delivered") }
        }.error.code)
    }

    @Test
    fun `deadline is checked while considering pairs that emit no variants`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(150)), clock = NanoClock { now++ })
        val base = CandidateGenerationFixtures.wideAllFilters().workload.queries.single()
        val predicates = base.predicates.take(16).map { it.copy(operator = ComparisonOperator.GREATER_THAN) }
        val query = base.copy(
            sql = "SELECT * FROM public.wide_records WHERE " + predicates.joinToString(" AND ") { "${it.leftColumn.name} > 0" },
            referencedColumns = predicates.map { it.leftColumn }.toSet(),
            predicates = predicates,
        )
        val workload = CandidateGenerationFixtures.workload(listOf(query))
        // With enough time there are only singles: no pair reaches emit's deadline check.
        val variants = collect(workload).single().variants
        assertEquals(16, variants.size)
        assertTrue(variants.all { it.definition.keys.size == 1 })

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AnalysisException::class.java) {
            enumerator.forEachScope(workload, context) { fail<Unit>("Unfinished scope delivered") }
        }.error.code)
    }

    @Test
    fun `deadline after a consumer prevents subsequent scopes`() {
        var now = 0L
        val context = RunContext(clock = NanoClock { now })
        context.startSearch()
        context.enterPhase(AnalysisPhase.CANDIDATES)
        var count = 0
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AnalysisException::class.java) {
            enumerator.forEachScope(maximumWorkload(), context) {
                count++
                now = Duration.ofSeconds(60).toNanos()
            }
        }.error.code)
        assertEquals(1, count)
        assertEquals(RunStatistics(), context.statistics()) // CPU generation does not spend evaluator counters.
    }

    @Test
    fun `consumer failure propagates and enumeration can be restarted`() {
        val failure = IllegalStateException("consumer failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            enumerator.forEachScope(maximumWorkload(), RunContext()) { throw failure }
        })
        val scenario = CandidateGenerationFixtures.twoEqualities()
        CandidateGenerationContract.assertScopes(scenario, collect(scenario.workload))
    }

    @Test
    fun `overflow and positive normalized frequency underflow are explicit numeric errors`() {
        val workload = ContractFixtures.resolvedWorkload()
        val ids = workload.queries.map { it.id }
        for (rates in listOf(listOf(Double.MAX_VALUE, Double.MAX_VALUE), listOf(Double.MIN_VALUE, 2.0))) {
            val invalid = workload.copy(input = workload.input.copy(profiles = listOf(Profile("bad", 1.0, ids.zip(rates).toMap()))))
            val error = assertThrows(AnalysisException::class.java) { collect(invalid) }.error
            assertEquals(ErrorCode.NUMERIC_ERROR, error.code)
            assertEquals("bad", error.profileId)
        }
    }

    private fun collect(workload: ResolvedWorkload): List<CandidateScope> = buildList {
        enumerator.forEachScope(workload, RunContext()) { add(it) }
    }

    private fun maximumWorkload(): ResolvedWorkload {
        val tables = (1..3).map { TableRef("public", "table$it") }.toSet()
        val columns = tables.flatMap { table -> (1..16).map { ColumnRef(table, "c$it") } }
        val queries = (1..20).map { index ->
            ResolvedQuery(
                "q$index", "synthetic resolved workload", tables, columns.toSet(),
                columns.map { ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE) }, emptyList(),
            )
        }
        return CandidateGenerationFixtures.workload(queries)
    }
}
