package ru.itmo.indexbudget.candidates

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.CandidateGenerationFixtures
import ru.itmo.indexbudget.fixtures.withSqlSemantics
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer

class BaselineCostCandidateRankingTest {
    private val generator = ProfileAwareCandidateGenerator()
    private val table = TableRef("public", "t")

    @Test
    fun `rare expensive query enters the bounded shortlist ahead of frequent cheap queries`() {
        val queries = (1..18).map { query("q$it", "c$it") }
        val workload = workload(queries, listOf(Profile("default", 1.0,
            queries.associate { it.id to if (it.id == "q18") 1.0 else 1000.0 })))
        val baseline = baseline(queries.associate { it.id to if (it.id == "q18") 2000.0 else 1.0 })

        val frequency = generator.generate(workload, catalog(queries), RunContext(), baseline)
        val cost = generator.generate(costAware(workload), catalog(queries), RunContext(), baseline)

        assertEquals(16, frequency.candidates.size)
        assertEquals(16, cost.candidates.size)
        assertTrue(frequency.candidates.none { "q18" in it.sourceQueryIds })
        assertEquals(setOf("q18"), cost.candidates.first().sourceQueryIds)
        assertEquals(frequency.diagnostics.localScopes, cost.diagnostics.localScopes)
        assertEquals(frequency.diagnostics.candidatesAfterGlobalDeduplication,
            cost.diagnostics.candidatesAfterGlobalDeduplication)
    }

    @Test
    fun `all source queries contribute once after candidate deduplication`() {
        val queries = listOf(query("q1", "shared"), query("q2", "shared"), query("q3", "other"))
        val workload = costAware(workload(queries, listOf(Profile("s", 1.0,
            mapOf("q1" to 100.0, "q2" to 100.0, "q3" to 190.0)))))
        val result = generator.generate(workload, catalog(queries), RunContext(),
            baseline(mapOf("q1" to 1.0, "q2" to 1.0, "q3" to 1.0)))

        assertEquals(2, result.candidates.size)
        assertEquals("shared", result.candidates.first().definition.keys.single().column.name)
        assertEquals(setOf("q1", "q2"), result.candidates.first().sourceQueryIds)
    }

    @Test
    fun `cost ranking preserves equal profile turns despite traffic and relative weights`() {
        val queries = (1..18).map { query("q$it", "c$it") }
        val profiles = listOf(
            Profile("a_busy", 1e100, queries.associate { it.id to if (it.id == "q18") 0.0 else 1e9 }),
            Profile("z_rare", 1e-100, queries.associate { it.id to if (it.id == "q18") 1.0 else 0.0 }),
        )
        val workload = costAware(workload(queries, profiles))
        val baseline = baseline(queries.associate { it.id to 2.0 })
        val result = generator.generate(workload, catalog(queries), RunContext(), baseline)
        assertEquals(setOf("q18"), result.candidates[1].sourceQueryIds)

        val weightedDifferently = workload.copy(input = workload.input.copy(profiles = profiles.map {
            it.copy(weight = if (it.id == "a_busy") 1e-100 else 1e100)
        }))
        assertEquals(result, generator.generate(weightedDifferently, catalog(queries), RunContext(), baseline))
    }

    @Test
    fun `cost ranking keeps single pair diversity and stable tie breaks under reordered input`() {
        val scenario = CandidateGenerationFixtures.wideAllFilters()
        val workload = costAware(scenario.workload)
        val baseline = baseline(mapOf("wide" to 17.0))
        val result = generator.generate(workload, scenario.catalog, RunContext(), baseline)
        val legacy = generator.generate(scenario.workload, scenario.catalog, RunContext())
        assertEquals(legacy, result)
        assertEquals(List(8) { listOf(1, 2) }.flatten(),
            result.candidates.map { it.definition.keys.size })

        val queries = listOf(query("q1", "c1"), query("q2", "c2"), query("q3", "c3"))
        val input = costAware(workload(queries, listOf(Profile("s", 1.0,
            mapOf("q1" to 1.0, "q2" to 2.0, "q3" to 4.0)))))
        val equalScores = baseline(mapOf("q1" to 4.0, "q2" to 2.0, "q3" to 1.0))
        val ordered = generator.generate(input, catalog(queries), RunContext(), equalScores)
        assertEquals(ordered.candidates.map { it.id }.sorted(), ordered.candidates.map { it.id })
        assertEquals(ordered, generator.generate(input.copy(queries = queries.reversed(),
            input = input.input.copy(profiles = input.input.profiles.map {
                it.copy(callsPerHour = it.callsPerHour.entries.reversed().associate { (id, value) -> id to value })
            })), catalog(queries.reversed()), RunContext(), equalScores.copy(
                costByQueryId = equalScores.costByQueryId.entries.reversed().associate { (id, value) -> id to value },
                plansByQueryId = equalScores.plansByQueryId.entries.reversed().associate { (id, value) -> id to value },
            )))
    }

    @Test
    fun `constant costs preserve INCLUDE caps and OR branch origins for both rankings`() {
        val table = TableRef("public", "records")
        val catalog = CatalogSnapshot(listOf(CatalogTable(table, listOf("a", "b", "c", "d").map {
            CatalogColumn(it, "int4", defaultOperatorClass = "pg_catalog.int4_ops")
        }))).withSqlSemantics()
        val analyzer = JSqlParserSqlAnalyzer()
        val examples = listOf(
            "SELECT a, b, c, d FROM public.records WHERE a=1 AND b=2 AND c=3 AND d=4",
            "SELECT * FROM public.records WHERE (a=1 AND b>2) OR (a>3 AND c=4)",
        )
        for ((index, sql) in examples.withIndex()) {
            val input = WorkloadInput(listOf(QueryInput("q", sql)),
                listOf(Profile("p", 1.0, mapOf("q" to 1.0))), Constraints(1_000_000, null))
            val context = RunContext()
            val resolved = analyzer.resolve(analyzer.parse(input, context), catalog, context)
            val baseline = baseline(mapOf("q" to 17.0))
            val legacy = generator.generate(resolved, catalog, RunContext())
            assertEquals(legacy, generator.generate(resolved, catalog, RunContext(), baseline))
            assertEquals(legacy, generator.generate(costAware(resolved), catalog, RunContext(), baseline))
            assertEquals(sql, resolved.queries.single().sql)
            if (index == 0) {
                val scope = legacy.diagnostics.localScopes.single()
                assertEquals(12, scope.enumeratedIncludeDefinitions)
                assertEquals(8, scope.retainedIncludeDefinitions)
                assertEquals(16, legacy.candidates.size)
                assertTrue(legacy.candidates.any { it.definition.includedColumns.isNotEmpty() })
            } else {
                assertTrue(legacy.candidates.all { it.sourceBranches.isNotEmpty() })
                assertEquals(setOf(listOf("a", "b"), listOf("c", "a")), legacy.candidates
                    .filter { it.definition.keys.size == 2 }.map { candidate ->
                        candidate.definition.keys.map { it.column.name }
                    }.toSet())
                assertTrue(legacy.candidates.flatMap { it.sourceBranches }.any { it.conditionPath.startsWith("condition/L") })
                assertTrue(legacy.candidates.flatMap { it.sourceBranches }.any { it.conditionPath.startsWith("condition/R") })
            }
        }
    }

    @Test
    fun `zero baseline costs have no shortlist support and all zero baseline yields no candidates`() {
        val queries = listOf(query("zero", "c1"), query("positive", "c2"))
        val workload = costAware(workload(queries))
        val mixed = generator.generate(workload, catalog(queries), RunContext(),
            baseline(mapOf("zero" to 0.0, "positive" to 1.0)))
        assertEquals(listOf(setOf("positive")), mixed.candidates.map { it.sourceQueryIds })
        assertEquals(1, mixed.diagnostics.decisions.count { it.reason == CandidateReason.ZERO_BASELINE_COST_SUPPORT })
        val zero = generator.generate(workload, catalog(queries), RunContext(),
            baseline(mapOf("zero" to 0.0, "positive" to 0.0)))
        assertTrue(zero.candidates.isEmpty())
        assertEquals(2, zero.diagnostics.decisions.count { it.reason == CandidateReason.ZERO_BASELINE_COST_SUPPORT })
        assertTrue(zero.diagnostics.decisions.none { it.reason == CandidateReason.GLOBAL_CANDIDATE_LIMIT })
    }

    @Test
    fun `zero support beyond the shortlist cap does not claim a global limit`() {
        val queries = (1..18).map { query("q$it", "c$it") }
        val result = generator.generate(costAware(workload(queries)), catalog(queries), RunContext(),
            baseline(queries.associate { it.id to 0.0 }))
        assertEquals(18, result.diagnostics.candidatesAfterGlobalDeduplication)
        assertTrue(result.candidates.isEmpty())
        assertEquals(18, result.diagnostics.decisions.count { it.reason == CandidateReason.ZERO_BASELINE_COST_SUPPORT })
        assertTrue(result.diagnostics.decisions.none { it.reason == CandidateReason.GLOBAL_CANDIDATE_LIMIT })
        assertTrue(result.diagnostics.warnings.none { it.code == WarningCode.GLOBAL_CANDIDATE_LIMIT })
    }

    @Test
    fun `a zero baseline profile does not suppress other profiles with positive baseline support`() {
        val queries = listOf(query("zero", "c1"), query("positive", "c2"))
        val profiles = listOf(
            Profile("a_zero", 1.0, mapOf("zero" to 10.0, "positive" to 0.0)),
            Profile("z_positive", 1.0, mapOf("zero" to 0.0, "positive" to 1.0)),
        )
        val result = generator.generate(costAware(workload(queries, profiles)), catalog(queries), RunContext(),
            baseline(mapOf("zero" to 0.0, "positive" to 2.0)))
        assertEquals(listOf(setOf("positive")), result.candidates.map { it.sourceQueryIds })
        assertEquals(1, result.diagnostics.decisions.count { it.reason == CandidateReason.ZERO_BASELINE_COST_SUPPORT })
    }

    @Test
    fun `baseline evaluation boundary rejects nonnumeric and negative costs before ranking`() {
        for (cost in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            assertThrows(IllegalArgumentException::class.java) { baseline(mapOf("q" to cost)) }
        }
    }

    @Test
    fun `frequency normalization boundaries still fail explicitly in the new ranking`() {
        val queries = listOf(query("q1", "c1"), query("q2", "c2"))
        val baseline = baseline(mapOf("q1" to 1.0, "q2" to 1.0))
        for (frequencies in listOf(
            mapOf("q1" to Double.MAX_VALUE, "q2" to Double.MAX_VALUE),
            mapOf("q1" to Double.MIN_VALUE, "q2" to Double.MAX_VALUE),
        )) {
            val workload = costAware(workload(queries, listOf(Profile("s", 1.0, frequencies))))
            val error = assertThrows(AnalysisException::class.java) {
                generator.generate(workload, catalog(queries), RunContext(), baseline)
            }
            assertEquals(ErrorCode.NUMERIC_ERROR, error.error.code)
            assertEquals("s", error.error.profileId)
        }
    }

    @Test
    fun `cost products retain positive subnormal support and do not overflow ranking`() {
        val queries = listOf(query("q1", "c1"), query("q2", "c2"))
        val tiny = costAware(workload(queries, listOf(Profile("s", 1.0, mapOf("q1" to 0.5, "q2" to 1.0)))))
        val tinyResult = generator.generate(tiny, catalog(queries), RunContext(),
            baseline(mapOf("q1" to Double.MIN_VALUE, "q2" to Double.MIN_VALUE)))
        assertEquals(2, tinyResult.candidates.size)
        assertEquals(setOf("q2"), tinyResult.candidates.first().sourceQueryIds)

        val huge = costAware(workload(queries, listOf(Profile("s", 1.0, mapOf("q1" to 2.0, "q2" to 1.0)))))
        val hugeResult = generator.generate(huge, catalog(queries), RunContext(),
            baseline(mapOf("q1" to Double.MAX_VALUE, "q2" to Double.MAX_VALUE)))
        assertEquals(2, hugeResult.candidates.size)
        assertEquals(setOf("q1"), hugeResult.candidates.first().sourceQueryIds)
    }

    @Test
    fun `cost ranking requires a complete empty-set baseline`() {
        val queries = listOf(query("q1", "c1"), query("q2", "c2"))
        val workload = costAware(workload(queries))
        val missing = assertThrows(AnalysisException::class.java) {
            generator.generate(workload, catalog(queries), RunContext())
        }
        assertEquals(ErrorCode.INTERNAL_ERROR, missing.error.code)
        val incomplete = assertThrows(AnalysisException::class.java) {
            generator.generate(workload, catalog(queries), RunContext(), baseline(mapOf("q1" to 1.0)))
        }
        assertEquals(ErrorCode.INTERNAL_ERROR, incomplete.error.code)
        val nonempty = assertThrows(AnalysisException::class.java) {
            generator.generate(workload, catalog(queries), RunContext(),
                baseline(mapOf("q1" to 1.0, "q2" to 1.0)).copy(candidateIds = listOf("selected")))
        }
        assertEquals(ErrorCode.INTERNAL_ERROR, nonempty.error.code)
    }

    private fun query(id: String, columnName: String): ResolvedQuery {
        val column = ColumnRef(table, columnName)
        return ResolvedQuery(id, "SELECT * FROM public.t WHERE $columnName = 1", setOf(table), setOf(column),
            listOf(ResolvedPredicate(column, ComparisonOperator.EQUAL, PredicateSource.WHERE)), emptyList())
    }

    private fun catalog(queries: List<ResolvedQuery>) = CatalogSnapshot(listOf(CatalogTable(table,
        queries.flatMap { it.referencedColumns }.distinct().map {
            CatalogColumn(it.name, "int4", defaultOperatorClass = "pg_catalog.int4_ops")
        })))

    private fun workload(queries: List<ResolvedQuery>, profiles: List<Profile> = listOf(
        Profile("s", 1.0, queries.associate { it.id to 1.0 }),
    )) = CandidateGenerationFixtures.workload(queries, profiles)

    private fun costAware(workload: ResolvedWorkload) = workload.copy(
        input = workload.input.copy(candidateRanking = CandidateRanking.BASELINE_COST))

    private fun baseline(costs: Map<String, Double>) = EvaluationResult(emptyList(), costs,
        costs.mapValues { ExplainPlan("{}") })
}
