package ru.itmo.indexbudget.optimizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.SearchScenarios
import java.time.Duration

class LimitedRepairOptimizerTest {
    private val metrics = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()

    @Test
    fun `repair finds both budget traps and synergy from the empty set`() {
        for (fixture in listOf(SearchScenarios.oneToTwo(), SearchScenarios.oneToTwoNoSynergy(),
            SearchScenarios.zeroToTwo())) {
            for (objective in Objective.entries) {
                val workload = fixture.copy(workload = fixture.workload.copy(objective = objective)).resolvedWorkload()
                val context = context()
                val evaluator = fixture.evaluator()
                val baseline = baseline(evaluator, workload, context)

                val result = optimize(workload, fixture.candidates.reversed(), baseline, evaluator, context)

                val expected = fixture.costs.keys.single { it.size == 2 }
                assertEquals(RunStatus.COMPLETED, result.status, fixture.name)
                assertEquals(expected, result.selected?.evaluation?.candidateIds, fixture.name)
                assertEquals(fixture.costs.getValue(expected), result.selected?.evaluation?.costByQueryId)
                assertEquals(1, context.statistics().repairAttempts)
                assertTrue(AnalysisPhase.REPAIR in result.diagnostics.completedPhases)
                assertEquals(result.bestRobust, result.bestMean)
            }
        }
    }

    @Test
    fun `repair examines the fixed empty S0 after improvement without recursing into triples`() {
        val workload = workload()
        val evaluator = CountingEvaluator(cost = { ids, _ ->
            when {
                ids == listOf("i00", "i01") -> 60.0
                ids == listOf("i00", "i02") -> 50.0
                ids.size == 2 -> 90.0
                ids.size > 2 -> 1.0
                else -> 100.0
            }
        })
        val context = context()
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates(4), baseline, evaluator, context)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf("i00", "i02"), result.selected?.evaluation?.candidateIds)
        assertEquals(6, context.statistics().repairAttempts)
        assertEquals(11, evaluator.calls.size)
        assertTrue(evaluator.calls.all { it.size <= 2 })
        assertTrue(listOf("i02", "i03") in evaluator.calls)
    }

    @Test
    fun `replacement cache hits reuse complete plans without evaluation EXPLAIN or repair slots`() {
        val workload = workload(maxIndexes = 1)
        val evaluator = CountingEvaluator(cost = { ids, _ ->
            when (ids) {
                listOf("i00") -> 50.0
                listOf("i01") -> 70.0
                listOf("i02") -> 80.0
                else -> 100.0
            }
        })
        val context = context()
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates(3), baseline, evaluator, context)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(RunStatistics(explainAttempts = 4, evaluationAttempts = 4,
            completedEvaluations = 4, cacheHits = 2), context.statistics())
        assertEquals(evaluator.calls.size, evaluator.calls.distinct().size)
        assertSame(evaluator.results.getValue(listOf("i00")), result.bestRobust?.evaluation)
        assertSame(result.bestRobust?.evaluation, result.bestMean?.evaluation)
    }

    @Test
    fun `failed repair evaluation consumes one slot and its incomplete vector cannot become incumbent`() {
        val workload = workload(queryCount = 2)
        val evaluator = CountingEvaluator(
            cost = { ids, _ -> if (ids == listOf("i02", "i03")) 60.0 else 100.0 },
            onQuery = { ids, query, _ ->
                if (ids == listOf("i00", "i01") && query == "q01") fail(ErrorCode.EXPLAIN_FAILED)
            },
        )
        val context = context()
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates(4), baseline, evaluator, context)

        assertEquals(RunStatus.PARTIAL, result.status)
        assertTrue(StopReason.EVALUATION_FAILED in result.diagnostics.stopReasons)
        assertEquals(listOf("i02", "i03"), result.selected?.evaluation?.candidateIds)
        assertEquals(6, context.statistics().repairAttempts)
        assertEquals(11, context.statistics().evaluationAttempts)
        assertEquals(10, context.statistics().completedEvaluations)
        assertEquals(22, context.statistics().explainAttempts)
        assertEquals(1, evaluator.calls.count { it == listOf("i00", "i01") })
        assertTrue(result.diagnostics.errors.any { it.code == ErrorCode.EXPLAIN_FAILED && it.queryId == "q01" })
    }

    @Test
    fun `known failed singleton is never retried as a repair replacement`() {
        val workload = workload(maxIndexes = 1)
        val evaluator = CountingEvaluator(
            cost = { ids, _ -> if (ids == listOf("i00")) 50.0 else 100.0 },
            onQuery = { ids, _, _ -> if (ids == listOf("i01")) fail(ErrorCode.EXPLAIN_FAILED) },
        )
        val context = context()
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates(2), baseline, evaluator, context)

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(listOf("i00"), result.selected?.evaluation?.candidateIds)
        assertEquals(3, evaluator.calls.size)
        assertEquals(0, context.statistics().repairAttempts)
        assertEquals(2, context.statistics().completedEvaluations)
    }

    @Test
    fun `both incumbents survive an error on the second query and preserve their original plans`() {
        val input = workload(queryCount = 2, maxIndexes = 1).input.copy(profiles = listOf(
            Profile("normal", 0.9, mapOf("q00" to 3000.0, "q01" to 100.0)),
            Profile("campaign", 0.1, mapOf("q00" to 100.0, "q01" to 3000.0)),
        ))
        val workload = resolved(input)
        val evaluator = CountingEvaluator(
            cost = { ids, query ->
                when (ids) {
                    listOf("i00") -> if (query == "q00") 100.0 else 30.0
                    listOf("i01") -> if (query == "q00") 40.0 else 100.0
                    listOf("i02") -> 0.0
                    else -> 100.0
                }
            },
            onQuery = { ids, query, _ ->
                if (ids == listOf("i02") && query == "q01") fail(ErrorCode.EXPLAIN_FAILED)
            },
        )
        val context = context()
        val baseline = baseline(evaluator, workload, context)
        val checkpoints = mutableListOf<SearchCheckpoint>()

        val result = optimize(workload, candidates(3), baseline, evaluator, context, checkpoints::add)

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(listOf("i00"), result.bestRobust?.evaluation?.candidateIds)
        assertEquals(listOf("i01"), result.bestMean?.evaluation?.candidateIds)
        assertSame(evaluator.results.getValue(listOf("i00")), result.bestRobust?.evaluation)
        assertSame(evaluator.results.getValue(listOf("i01")), result.bestMean?.evaluation)
        assertEquals(result.bestRobust, checkpoints.last().bestRobust)
        assertEquals(result.bestMean, checkpoints.last().bestMean)
        assertEquals(4, evaluator.calls.size)
        assertEquals(3, context.statistics().completedEvaluations)
    }

    @Test
    fun `repair updates both objectives from the same pool instead of stopping after its first improvement`() {
        for (objective in Objective.entries) {
            val input = workload(queryCount = 2).input.copy(objective = objective, profiles = listOf(
                Profile("normal", 0.9, mapOf("q00" to 3000.0, "q01" to 100.0)),
                Profile("campaign", 0.1, mapOf("q00" to 100.0, "q01" to 3000.0)),
            ))
            val workload = resolved(input)
            val evaluator = CountingEvaluator(cost = { ids, query ->
                when (ids) {
                    listOf("i00", "i01") -> if (query == "q00") 40.0 else 100.0
                    listOf("i00", "i02") -> if (query == "q00") 100.0 else 30.0
                    else -> 100.0
                }
            })
            val context = context()
            val baseline = baseline(evaluator, workload, context)

            val result = optimize(workload, candidates(3), baseline, evaluator, context)

            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(listOf("i00", "i02"), result.bestRobust?.evaluation?.candidateIds)
            assertEquals(listOf("i00", "i01"), result.bestMean?.evaluation?.candidateIds)
            assertSame(evaluator.results.getValue(listOf("i00", "i02")), result.bestRobust?.evaluation)
            assertSame(evaluator.results.getValue(listOf("i00", "i01")), result.bestMean?.evaluation)
            assertEquals(3, context.statistics().repairAttempts)
        }
    }

    @Test
    fun `a returned partial vector is discarded and search continues with the previous full result`() {
        val workload = workload(queryCount = 2, maxIndexes = 1)
        val evaluator = CountingEvaluator(
            cost = { ids, _ -> if (ids == listOf("i00")) 50.0 else if (ids.isEmpty()) 100.0 else 0.0 },
            afterComplete = { ids, _, evaluation ->
                if (ids == listOf("i01")) evaluation.copy(
                    costByQueryId = mapOf("q00" to 0.0),
                    plansByQueryId = mapOf("q00" to ExplainPlan("{}")),
                ) else evaluation
            },
        )
        val context = context()
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates(2), baseline, evaluator, context)

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(listOf("i00"), result.selected?.evaluation?.candidateIds)
        assertEquals(setOf("q00", "q01"), result.selected?.evaluation?.costByQueryId?.keys)
        assertTrue(StopReason.EVALUATION_FAILED in result.diagnostics.stopReasons)
        assertEquals(1, evaluator.calls.count { it == listOf("i01") })
    }

    @Test
    fun `a local hypothetical index error excludes its candidate while the remaining pair is explored`() {
        val workload = workload()
        val evaluator = CountingEvaluator(
            cost = { ids, _ -> if (ids == listOf("i00", "i01")) 50.0 else 100.0 },
            onQuery = { ids, _, _ ->
                if ("i02" in ids) throw AnalysisException(AnalysisError(ErrorCode.HYPOTHETICAL_INDEX_ERROR,
                    "Fixture candidate cannot be created", details = mapOf("candidate_id" to "i02")))
            },
        )
        val context = context()
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates(3), baseline, evaluator, context)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf("i00", "i01"), result.selected?.evaluation?.candidateIds)
        assertEquals(listOf(listOf("i02")), evaluator.calls.filter { "i02" in it })
        assertTrue(result.diagnostics.warnings.any { it.code == WarningCode.CANDIDATE_REJECTED })
        assertTrue(result.diagnostics.candidateDecisions.any {
            it.reason == CandidateReason.HYPOTHETICAL_INDEX_ERROR && it.candidateId == "i02"
        })
    }

    @Test
    fun `connection EXPLAIN and cancellation limits return saved full incumbents`() {
        for ((code, reason) in listOf(ErrorCode.DB_CONNECTION_ERROR to StopReason.CONNECTION_LOST,
            ErrorCode.EXPLAIN_LIMIT to StopReason.EXPLAIN_LIMIT,
            ErrorCode.CANCELLED to StopReason.CANCELLED)) {
            val workload = workload(queryCount = 2)
            val evaluator = CountingEvaluator(
                cost = { ids, _ -> if (ids == listOf("i00")) 50.0 else 100.0 },
                onQuery = { ids, query, _ ->
                    if (ids == listOf("i01") && query == "q01") fail(code)
                },
            )
            val context = context()
            val baseline = baseline(evaluator, workload, context)

            val result = optimize(workload, candidates(3), baseline, evaluator, context)

            assertEquals(RunStatus.PARTIAL, result.status, code.name)
            assertTrue(reason in result.diagnostics.stopReasons, code.name)
            assertEquals(listOf("i00"), result.bestRobust?.evaluation?.candidateIds)
            assertEquals(result.bestRobust, result.bestMean)
            assertFalse(evaluator.calls.any { "i02" in it })
            assertSame(evaluator.results.getValue(listOf("i00")), result.selected?.evaluation)
        }
    }

    @Test
    fun `a late complete evaluation is checkpointed before the shared search deadline stops work`() {
        var now = 0L
        val workload = workload()
        val context = context(clock = NanoClock { now })
        val evaluator = CountingEvaluator(
            cost = { ids, _ -> if (ids == listOf("i00")) 50.0 else 100.0 },
            afterComplete = { ids, _, evaluation ->
                if (ids == listOf("i00")) now = Duration.ofSeconds(60).toNanos()
                evaluation
            },
        )
        val baseline = baseline(evaluator, workload, context)
        val checkpoints = mutableListOf<SearchCheckpoint>()

        val result = optimize(workload, candidates(3), baseline, evaluator, context, checkpoints::add)

        assertEquals(RunStatus.PARTIAL, result.status)
        assertTrue(StopReason.DEADLINE_EXCEEDED in result.diagnostics.stopReasons)
        assertEquals(listOf("i00"), result.selected?.evaluation?.candidateIds)
        assertEquals(listOf(emptyList(), listOf("i00")), evaluator.calls)
        assertEquals(result.bestRobust, checkpoints.last().bestRobust)
    }

    @Test
    fun `numeric errors remain fatal even after an improving full evaluation`() {
        val workload = workload(queryCount = 2, maxIndexes = 1)
        val evaluator = CountingEvaluator(cost = { ids, _ ->
            when (ids) {
                listOf("i00") -> 50.0
                listOf("i01") -> Double.MAX_VALUE
                else -> 100.0
            }
        })
        val context = context()
        val baseline = baseline(evaluator, workload, context)

        val error = assertThrows(AnalysisException::class.java) {
            optimize(workload, candidates(2), baseline, evaluator, context)
        }

        assertEquals(ErrorCode.NUMERIC_ERROR, error.error.code)
    }

    @Test
    fun `repair limit is partial only when admissible untested neighbors remain`() {
        for (candidateCount in listOf(8, 9)) {
            val workload = workload()
            val evaluator = CountingEvaluator()
            val context = context()
            val baseline = baseline(evaluator, workload, context)

            val result = optimize(workload, candidates(candidateCount), baseline, evaluator, context)

            assertEquals(28, context.statistics().repairAttempts)
            assertEquals(1 + candidateCount + 28, context.statistics().evaluationAttempts)
            if (candidateCount == 8) {
                assertEquals(RunStatus.COMPLETED, result.status)
                assertFalse(StopReason.REPAIR_LIMIT in result.diagnostics.stopReasons)
                assertTrue(AnalysisPhase.REPAIR in result.diagnostics.completedPhases)
            } else {
                assertEquals(RunStatus.PARTIAL, result.status)
                assertTrue(StopReason.REPAIR_LIMIT in result.diagnostics.stopReasons)
                assertFalse(AnalysisPhase.REPAIR in result.diagnostics.completedPhases)
            }
        }
    }

    @Test
    fun `budget rejected neighbors after the twenty eighth attempt do not turn exhaustion into partial`() {
        val initial = workload().input
        val workload = resolved(initial.copy(constraints = initial.constraints.copy(storageBudgetBytes = 120)))
        val candidates = candidates(9).map { if (it.id == "i08") it.copy(estimatedBytes = 100) else it }
        val evaluator = CountingEvaluator()
        val context = context()
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates, baseline, evaluator, context)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(28, context.statistics().repairAttempts)
        assertEquals(38, context.statistics().completedEvaluations)
        assertFalse(evaluator.calls.any { it.size == 2 && "i08" in it })
        assertTrue(AnalysisPhase.REPAIR in result.diagnostics.completedPhases)
    }

    @Test
    fun `maximal normal search uses 99 shared vectors and 1980 EXPLAIN attempts regardless of profile count`() {
        for (profileCount in listOf(1, 3)) {
            val workload = workload(queryCount = 20, profileCount = profileCount)
            val evaluator = CountingEvaluator(cost = { ids, _ -> 100.0 - 10.0 * ids.size })
            val context = context()
            val baseline = baseline(evaluator, workload, context)

            val result = optimize(workload, candidates(16).reversed(), baseline, evaluator, context)

            assertEquals(RunStatus.PARTIAL, result.status)
            assertTrue(StopReason.REPAIR_LIMIT in result.diagnostics.stopReasons)
            assertEquals(99, context.statistics().evaluationAttempts)
            assertEquals(99, context.statistics().completedEvaluations)
            assertEquals(1980, context.statistics().explainAttempts)
            assertEquals(28, context.statistics().repairAttempts)
            assertEquals(99, evaluator.calls.distinct().size)
            assertEquals(listOf("i00", "i01", "i02", "i03", "i04"), result.selected?.evaluation?.candidateIds)
        }
    }

    @Test
    fun `one optimizer instance does not share its cache between invocations`() {
        val optimizer = LimitedRepairOptimizer()
        val workload = workload(maxIndexes = 1)
        val selectedCosts = mutableListOf<Double>()
        for (cost in listOf(50.0, 70.0)) {
            val evaluator = CountingEvaluator(cost = { ids, _ -> if (ids.isEmpty()) 100.0 else cost })
            val context = context()
            val baseline = baseline(evaluator, workload, context)

            val result = optimizer.optimize(workload, CandidateBatch(candidates(1)), baseline, evaluator,
                metrics, comparator, context)

            assertEquals(2, evaluator.calls.size)
            selectedCosts += checkNotNull(result.selected).evaluation.costByQueryId.getValue("q00")
        }
        assertEquals(listOf(50.0, 70.0), selectedCosts)
    }

    @Test
    fun `repair round robin filters inadmissible structures before interleaving all three queues`() {
        val initial = workload(maxIndexes = 3).input
        val workload = resolved(initial.copy(constraints = initial.constraints.copy(storageBudgetBytes = 120)))
        val candidates = candidates(5).map {
            it.copy(estimatedBytes = when (it.id) {
                "i00" -> 40
                "i01" -> 50
                "i02" -> 11
                else -> 1
            })
        }
        val events = mutableListOf<String>()
        val evaluator = CountingEvaluator(
            cost = { ids, _ -> when {
                ids.isEmpty() -> 100.0
                ids == listOf("i00") -> 50.0
                "i00" in ids -> 60.0
                ids.size == 1 -> 75.0 + 5.0 * ids.single().removePrefix("i").toInt()
                else -> 90.0
            } },
            onQuery = { ids, _, run ->
                if (run.phase == AnalysisPhase.REPAIR) events += ids.joinToString(",")
            },
        )
        val context = context()
        val baseline = baseline(evaluator, workload, context)
        var observedCacheHits = 0
        context.observeProgress { progress ->
            if (progress.phase == AnalysisPhase.REPAIR && progress.statistics.cacheHits > observedCacheHits) {
                events += "cache"
            }
            observedCacheHits = progress.statistics.cacheHits
        }

        val result = optimize(workload, candidates.reversed(), baseline, evaluator, context)

        assertEquals(RunStatus.COMPLETED, result.status)
        // i00+i01+i02 costs 122 bytes, so the first 0→2 turn must already use the next admissible pair.
        assertEquals(listOf(
            "i00,i01,i03", "cache", "i01,i02",
            "i00,i01,i04", "cache", "i01,i03",
            "i00,i02,i03", "cache", "i01,i04",
            "i00,i02,i04", "cache", "i02,i03",
            "i00,i03,i04", "i02,i04", "i03,i04",
        ), events)
        assertEquals(11, context.statistics().repairAttempts)
        assertEquals(4, context.statistics().cacheHits)
        assertFalse(evaluator.calls.contains(listOf("i00", "i01", "i02")))
    }

    @Test
    fun `the real shared EXPLAIN counter stops halfway through a set without replacing saved results`() {
        val workload = workload(queryCount = 2)
        val evaluator = CountingEvaluator(cost = { ids, _ -> if (ids.isEmpty()) 100.0 else 50.0 })
        val context = context(limits = RunLimits(maxExplainAttempts = 5))
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates(3), baseline, evaluator, context)

        assertEquals(RunStatus.PARTIAL, result.status)
        assertTrue(StopReason.EXPLAIN_LIMIT in result.diagnostics.stopReasons)
        assertEquals(RunStatistics(explainAttempts = 5, evaluationAttempts = 3, completedEvaluations = 2),
            context.statistics())
        assertEquals(listOf(emptyList(), listOf("i00"), listOf("i01")), evaluator.calls)
        assertSame(evaluator.results.getValue(listOf("i00")), result.bestRobust?.evaluation)
        assertEquals(result.bestRobust, result.bestMean)
        assertEquals(setOf("q00", "q01"), result.selected?.evaluation?.plansByQueryId?.keys)
    }

    @Test
    fun `a repair hypothesis error blocks only the named member unless cleanup loses the session`() {
        for (cleanupFails in listOf(false, true)) {
            val workload = workload()
            val evaluator = CountingEvaluator(
                cost = { ids, _ -> if (ids == listOf("i01", "i02")) 50.0 else 100.0 },
                onQuery = { ids, _, run ->
                    if (run.phase == AnalysisPhase.REPAIR && ids == listOf("i00", "i01")) {
                        val failure = AnalysisException(AnalysisError(ErrorCode.HYPOTHETICAL_INDEX_ERROR,
                            "Fixture candidate failed", details = mapOf("candidate_id" to "i00")))
                        if (cleanupFails) failure.addSuppressed(AnalysisException(AnalysisError(
                            ErrorCode.DB_CONNECTION_ERROR, "Fixture cleanup lost the session")))
                        throw failure
                    }
                },
            )
            val context = context()
            val baseline = baseline(evaluator, workload, context)

            val result = optimize(workload, candidates(3), baseline, evaluator, context)

            assertTrue(result.diagnostics.candidateDecisions.any {
                it.reason == CandidateReason.HYPOTHETICAL_INDEX_ERROR && it.candidateId == "i00" &&
                    it.phase == AnalysisPhase.REPAIR
            })
            assertFalse(evaluator.calls.contains(listOf("i00", "i02")))
            if (cleanupFails) {
                assertEquals(RunStatus.PARTIAL, result.status)
                assertTrue(StopReason.CONNECTION_LOST in result.diagnostics.stopReasons)
                assertTrue(result.diagnostics.errors.any { it.code == ErrorCode.DB_CONNECTION_ERROR })
                assertTrue(result.diagnostics.errors.any { it.code == ErrorCode.HYPOTHETICAL_INDEX_ERROR })
                assertEquals(emptyList<String>(), result.selected?.evaluation?.candidateIds)
                assertFalse(evaluator.calls.contains(listOf("i01", "i02")))
                assertEquals(1, context.statistics().repairAttempts)
            } else {
                assertEquals(RunStatus.COMPLETED, result.status)
                assertEquals(listOf("i01", "i02"), result.selected?.evaluation?.candidateIds)
                assertEquals(2, context.statistics().repairAttempts)
                assertTrue(result.diagnostics.errors.isEmpty())
            }
        }
    }

    @Test
    fun `zero baseline regression remains a cached full rejection and never replaces either incumbent`() {
        val input = workload(queryCount = 2, maxIndexes = 1).input.copy(profiles = listOf(
            Profile("active", 1.0, mapOf("q00" to 1.0, "q01" to 0.0)),
            Profile("zero", 1.0, mapOf("q00" to 0.0, "q01" to 1.0)),
        ))
        val workload = resolved(input)
        val evaluator = CountingEvaluator(cost = { ids, query ->
            when {
                ids == listOf("i01") -> if (query == "q00") 0.0 else 1.0
                query == "q01" -> 0.0
                ids == listOf("i00") -> 50.0
                else -> 100.0
            }
        })
        val context = context()
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates(2), baseline, evaluator, context)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf("i00"), result.bestRobust?.evaluation?.candidateIds)
        assertEquals(result.bestRobust, result.bestMean)
        assertEquals(0.0, result.selected?.evaluation?.costByQueryId?.get("q01"))
        assertEquals(RunStatistics(explainAttempts = 6, evaluationAttempts = 3, completedEvaluations = 3,
            cacheHits = 1), context.statistics())
        assertEquals(1, evaluator.calls.count { it == listOf("i01") })
        assertTrue(result.diagnostics.warnings.any {
            it.profileId == "zero" && it.details["code"] == ErrorCode.ZERO_BASELINE_REGRESSION.name
        })
    }

    @Test
    fun `a repair combination violating a cost guard is rejected and keeps both incumbents on the baseline`() {
        val initial = workload(queryCount = 2).input
        val workload = resolved(initial.copy(constraints =
            initial.constraints.copy(queryCostGuards = mapOf("q00" to 1.0))))
        // Singletons keep the baseline costs, so greedy stays on the empty set; only the pair
        // improves the mean, and it doubles the guarded q00 planner cost.
        val evaluator = CountingEvaluator(cost = { ids, query ->
            when {
                ids == listOf("i00", "i01") -> if (query == "q00") 110.0 else 1.0
                else -> 100.0
            }
        })
        val context = context()
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates(2), baseline, evaluator, context)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertTrue(evaluator.calls.contains(listOf("i00", "i01")))
        assertEquals(emptyList<String>(), result.bestRobust?.evaluation?.candidateIds)
        assertEquals(emptyList<String>(), result.bestMean?.evaluation?.candidateIds)
        assertEquals(emptyList<String>(), result.selected?.evaluation?.candidateIds)
        assertEquals(1, context.statistics().repairAttempts)
        assertTrue(result.diagnostics.warnings.any {
            it.code == WarningCode.CANDIDATE_REJECTED && it.queryId == "q00" &&
                it.details["code"] == ErrorCode.CRITICAL_QUERY_REGRESSION.name
        })
    }

    @Test
    fun `cache hits remain free after the repair cap while only new admissible neighbors are deferred`() {
        val workload = workload(maxIndexes = 3)
        val evaluator = CountingEvaluator(cost = { ids, _ -> if ("i00" in ids) 50.0 else 100.0 })
        val context = context(limits = RunLimits(maxRepairAttempts = 1))
        val baseline = baseline(evaluator, workload, context)

        val result = optimize(workload, candidates(3), baseline, evaluator, context)

        assertEquals(RunStatus.PARTIAL, result.status)
        assertTrue(StopReason.REPAIR_LIMIT in result.diagnostics.stopReasons)
        assertEquals(1L, result.diagnostics.unexaminedConfigurations)
        assertEquals(RunStatistics(explainAttempts = 7, evaluationAttempts = 7, completedEvaluations = 7,
            cacheHits = 2, repairAttempts = 1), context.statistics())
        assertEquals(listOf("i00", "i01", "i02"), evaluator.calls.last())
        assertFalse(evaluator.calls.contains(listOf("i01", "i02")))
        assertEquals(evaluator.calls.size, evaluator.calls.distinct().size)
        assertEquals(listOf("i00"), result.selected?.evaluation?.candidateIds)
    }

    private fun optimize(
        workload: ResolvedWorkload,
        candidates: List<IndexCandidate>,
        baseline: EvaluationResult,
        evaluator: ConfigurationEvaluator,
        context: RunContext,
        onCheckpoint: (SearchCheckpoint) -> Unit = {},
    ): SearchResult = LimitedRepairOptimizer().optimize(workload, CandidateBatch(candidates), baseline,
        evaluator, metrics, comparator, context, onCheckpoint)

    private fun context(clock: NanoClock = NanoClock { 0L }, limits: RunLimits = RunLimits()): RunContext =
        RunContext(clock = clock, limits = limits).also { it.startSearch(); it.enterPhase(AnalysisPhase.BASELINE) }

    private fun baseline(
        evaluator: ConfigurationEvaluator,
        workload: ResolvedWorkload,
        context: RunContext,
    ): EvaluationResult = evaluator.evaluate(emptyList(), workload, context).also { context.completePhase() }

    private fun workload(queryCount: Int = 1, profileCount: Int = 1, maxIndexes: Int = 5): ResolvedWorkload {
        val queries = (0 until queryCount).map { QueryInput("q%02d".format(it), "SELECT 1") }
        return resolved(WorkloadInput(queries,
            (0 until profileCount).map { Profile("p$it", 1.0, queries.associate { query -> query.id to 1.0 }) },
            Constraints(1_000_000, null, maxIndexes)))
    }

    private fun resolved(input: WorkloadInput) = ResolvedWorkload(input, input.queries.map {
        ResolvedQuery(it.id, it.sql, emptySet(), emptySet(), emptyList(), emptyList())
    })

    private fun candidates(count: Int): List<IndexCandidate> {
        val table = TableRef("public", "records")
        return (0 until count).map {
            IndexCandidate("i%02d".format(it),
                IndexDefinition(table, listOf(IndexKey(ColumnRef(table, "column_$it")))),
                emptySet(), setOf(GenerationReason.SINGLE_COLUMN), estimatedBytes = 1)
        }
    }

    private fun fail(code: ErrorCode): Nothing = throw AnalysisException(AnalysisError(code,
        "Fixture evaluation failed", queryId = "q01"))

    private class CountingEvaluator(
        private val cost: (List<String>, String) -> Double = { _, _ -> 100.0 },
        private val onQuery: (List<String>, String, RunContext) -> Unit = { _, _, _ -> },
        private val afterComplete: (List<String>, RunContext, EvaluationResult) -> EvaluationResult =
            { _, _, evaluation -> evaluation },
    ) : ConfigurationEvaluator {
        val calls = mutableListOf<List<String>>()
        val results = mutableMapOf<List<String>, EvaluationResult>()

        override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
            context: RunContext): EvaluationResult {
            context.reserveEvaluationAttempt()
            val ids = candidates.map { it.id }.sorted()
            calls += ids
            val costs = workload.queries.sortedBy { it.id }.associate { query ->
                context.reserveExplainAttempt()
                onQuery(ids, query.id, context)
                query.id to cost(ids, query.id)
            }
            val evaluation = EvaluationResult(ids, costs, costs.mapValues { (query, value) ->
                ExplainPlan("""{"fixture_set":"${ids.joinToString(",")}","query":"$query","cost":$value}""")
            })
            context.recordCompletedEvaluation()
            return afterComplete(ids, context, evaluation).also { results[ids] = it }
        }
    }
}
