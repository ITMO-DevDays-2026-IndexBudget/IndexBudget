package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.cli.IndexBudgetCli
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.optimizer.ConfigurationEvaluator
import ru.itmo.indexbudget.optimizer.Optimizer
import ru.itmo.indexbudget.optimizer.SearchCheckpoint
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.report.ReportBuilder
import ru.itmo.indexbudget.report.ReportExporter
import ru.itmo.indexbudget.sql.ParsedWorkload
import ru.itmo.indexbudget.sql.SqlAnalyzer
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class AnalysisPipelineTest {
    @Test
    fun `real budget calculator supplies the same expenses to search and report`() {
        val run = Harness(suppliedMetrics = MetricsCalculatorImpl())
        val result = run.execute()

        assertEquals(RunStatus.COMPLETED, result.status)
        val reported = checkNotNull(run.report).candidates.candidates
        assertEquals(listOf(262_144L, 524_288L), reported.map { it.estimatedBytes })
        assertEquals(listOf(314_573L, 629_146L), reported.map { it.budgetedBytes })
        assertEquals(listOf(4100.0, 8200.0), reported.map { it.writeUnitsPerHour })
        assertEquals(BudgetUsage(262_144, 314_573, 4100.0, 1), result.bestRobust?.metrics?.budgetUsage)
        assertEquals(BudgetUsage(524_288, 629_146, 8200.0, 1), result.bestMean?.metrics?.budgetUsage)
        assertSame(result, run.report?.result)
        assertEquals(3, run.evaluatedSets.size)
    }

    @Test
    fun `one session and context connect all stages and reports use only saved evaluations`() {
        val run = Harness()
        val result = run.execute()

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf(emptyList(), listOf("records_created_at"), listOf("records_group_created_at")),
            run.evaluatedSets)
        assertEquals(2, run.input.profiles.size)
        assertEquals(1, run.openCalls)
        assertEquals(1, run.closeCalls)
        assertEquals(0, run.cancelCalls)
        assertEquals(1, run.unregisterCalls)
        assertEquals(RunStatistics(6, 3, 3, 1, 1), result.diagnostics.statistics)
        assertEquals(AnalysisPhase.entries.toList(), run.progress.map { it.phase }.distinct())
        assertEquals(AnalysisPhase.entries.filter { it != AnalysisPhase.REPORT }, result.diagnostics.completedPhases)
        assertEquals(AnalysisPhase.entries.toList(), run.context.completedPhases())
        assertTrue(run.events.indexOf("close") < run.events.indexOf("report"))
        assertTrue(run.events.indexOf("unregister") < run.events.indexOf("report"))
        assertEquals(listOf("report", "export"), run.events.takeLast(2))
        assertSame(result, run.report?.result)
        assertEquals("a".repeat(64), run.report?.inputHash)
        assertEquals(ContractFixtures.baselineEvaluation().plansByQueryId, run.report?.result?.baseline?.plansByQueryId)
        assertEquals(ContractFixtures.robustEvaluation().plansByQueryId, result.bestRobust?.evaluation?.plansByQueryId)
        assertEquals(ContractFixtures.meanEvaluation().plansByQueryId, result.bestMean?.evaluation?.plansByQueryId)
        assertEquals(listOf(262_144L, 524_288L), run.report?.candidates?.candidates?.map { it.estimatedBytes })
        assertEquals(listOf(314_573L, 629_146L), run.report?.candidates?.candidates?.map { it.budgetedBytes })
    }

    @Test
    fun `baseline failure and incomplete baseline cannot produce partial`() {
        for (incomplete in listOf(false, true)) {
            val run = Harness()
            run.baselineAction = {
                if (!incomplete) problem(ErrorCode.EXPLAIN_FAILED)
                val baseline = ContractFixtures.baselineEvaluation()
                baseline.copy(costByQueryId = baseline.costByQueryId.filterKeys { it == "records_by_group" },
                    plansByQueryId = baseline.plansByQueryId.filterKeys { it == "records_by_group" })
            }
            val result = run.execute()

            assertEquals(RunStatus.FAILED, result.status)
            assertNull(result.baseline)
            assertNull(result.selected)
            assertFalse("generate" in run.events)
            assertFalse("optimize" in run.events)
            assertEquals(1, run.closeCalls)
            assertEquals(0, result.diagnostics.statistics.completedEvaluations)
            assertEquals(ErrorCode.EXPLAIN_FAILED, (result as SearchResult.Failed).error.code)
        }
    }

    @Test
    fun `zero storage and zero max indexes return evaluated empty set without candidates`() {
        for (reason in listOf(StopReason.ZERO_STORAGE_BUDGET, StopReason.ZERO_MAX_INDEXES)) {
            val input = ContractFixtures.workload().let {
                it.copy(constraints = it.constraints.copy(
                    storageBudgetBytes = if (reason == StopReason.ZERO_STORAGE_BUDGET) 0 else it.constraints.storageBudgetBytes,
                    maxIndexes = if (reason == StopReason.ZERO_MAX_INDEXES) 0 else it.constraints.maxIndexes,
                ))
            }
            val run = Harness(input, suppliedMetrics = MetricsCalculatorImpl())
            val result = run.execute()

            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(listOf(reason), result.diagnostics.stopReasons)
            assertEquals(emptyList<String>(), result.selected?.evaluation?.candidateIds)
            assertEquals(listOf(emptyList<String>()), run.evaluatedSets)
            assertFalse("generate" in run.events)
            assertFalse("optimize" in run.events)
            assertEquals(2, result.diagnostics.statistics.explainAttempts)
        }
    }

    @Test
    fun `all zero baseline costs return empty set before candidate generation`() {
        for (objective in Objective.entries) {
            for (withWriteProfile in listOf(false, true)) {
                val run = Harness(
                    ContractFixtures.workload(objective, withWriteProfile),
                    suppliedMetrics = MetricsCalculatorImpl(),
                )
                run.baselineAction = {
                    val baseline = ContractFixtures.baselineEvaluation()
                    baseline.copy(costByQueryId = baseline.costByQueryId.mapValues { 0.0 })
                }
                val result = run.execute()

                assertEquals(RunStatus.COMPLETED, result.status)
                assertEquals(listOf(StopReason.ZERO_BASELINE), result.diagnostics.stopReasons)
                assertNull(result.selected?.metrics?.meanRatio)
                assertNull(result.selected?.metrics?.worstRatio)
                assertEquals(emptyList<String>(), result.selected?.evaluation?.candidateIds)
                assertSame(result.bestRobust, result.bestMean)
                assertSame(result, run.report?.result)
                assertEquals(listOf(emptyList<String>()), run.evaluatedSets)
                assertEquals(1, result.diagnostics.statistics.completedEvaluations)
                assertEquals(2, result.diagnostics.statistics.explainAttempts)
                assertFalse("generate" in run.events)
                assertFalse("optimize" in run.events)
                assertFalse(run.events.any { it.startsWith("size:") })
                assertEquals(1, run.closeCalls)
                assertEquals(1, run.unregisterCalls)
                assertEquals(listOf("report", "export"), run.events.takeLast(2))
                if (withWriteProfile) {
                    assertEquals(0.0, result.selected?.metrics?.budgetUsage?.writeUnitsPerHour)
                } else {
                    assertNull(result.selected?.metrics?.budgetUsage?.writeUnitsPerHour)
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Objective::class)
    fun `zero-baseline rejection preserves incumbents and does not fail the run`(objective: Objective) {
        val acceptedIds = listOf("records_created_at")
        val rejectedIds = listOf("records_group_created_at")
        for ((first, second) in listOf(acceptedIds to rejectedIds, rejectedIds to acceptedIds)) {
            val input = ContractFixtures.workload(objective).copy(profiles = listOf(
                Profile("zero", 1.0, mapOf("records_by_group" to 1.0, "records_by_value" to 0.0)),
                Profile("positive", 1.0, mapOf("records_by_group" to 0.0, "records_by_value" to 1.0)),
            ))
            val run = Harness(input, suppliedMetrics = MetricsCalculatorImpl())
            run.baselineAction = {
                ContractFixtures.baselineEvaluation().copy(costByQueryId = mapOf(
                    "records_by_group" to 0.0, "records_by_value" to 100.0,
                ))
            }
            run.scriptedEvaluations[acceptedIds] = ContractFixtures.robustEvaluation().copy(costByQueryId = mapOf(
                "records_by_group" to 0.0, "records_by_value" to 50.0,
            ))
            run.scriptedEvaluations[rejectedIds] = ContractFixtures.meanEvaluation().copy(costByQueryId = mapOf(
                "records_by_group" to 1.0, "records_by_value" to 1.0,
            ))
            run.greedyConfigurationIds = first
            run.repairConfigurationIds = second
            run.robustIncumbentIds = acceptedIds
            run.meanIncumbentIds = acceptedIds

            val result = run.execute()

            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(acceptedIds, result.bestRobust?.evaluation?.candidateIds)
            assertEquals(acceptedIds, result.bestMean?.evaluation?.candidateIds)
            assertEquals(0.5, result.selected?.metrics?.meanRatio)
            assertEquals(0.5, result.selected?.metrics?.worstRatio)
            assertEquals(listOf(emptyList(), first, second), run.evaluatedSets)
            val rejection = result.diagnostics.errors.single()
            assertEquals(ErrorCode.ZERO_BASELINE_REGRESSION, rejection.code)
            assertEquals("zero", rejection.profileId)
            assertEquals(listOf(StopReason.SEARCH_COMPLETE), result.diagnostics.stopReasons)
            assertSame(result, run.report?.result)
            assertEquals(1, run.closeCalls)
        }
    }

    @Test
    fun `parsing overrun is detected before opening a session`() {
        val run = Harness()
        run.onParse = { run.advance(90) }
        val result = run.execute()

        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, (result as SearchResult.Failed).error.code)
        assertEquals(0, run.openCalls)
        assertFalse("catalog" in run.events)
        assertEquals(Duration.ZERO, run.exportRemaining)
        assertEquals(1, run.unregisterCalls)
    }

    @Test
    fun `complete baseline returned after search deadline is retained for partial export`() {
        val run = Harness()
        run.afterBaseline = { run.advance(60) }
        val result = run.execute()

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(ContractFixtures.baselineEvaluation(), result.baseline)
        assertEquals(listOf(StopReason.DEADLINE_EXCEEDED), result.diagnostics.stopReasons)
        assertEquals(Duration.ofSeconds(30), run.exportRemaining)
        assertFalse(AnalysisPhase.BASELINE in result.diagnostics.completedPhases)
        assertEquals(1, run.cancelCalls)
        assertEquals(1, run.closeCalls)
    }

    @Test
    fun `candidate generation deadline saves baseline and uses the remaining command budget`() {
        val run = Harness()
        run.onGenerate = { run.advance(60) }
        val result = run.execute()

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(listOf(StopReason.DEADLINE_EXCEEDED), result.diagnostics.stopReasons)
        assertEquals(emptyList<String>(), result.selected?.evaluation?.candidateIds)
        assertFalse("size:records_created_at" in run.events)
        assertFalse("optimize" in run.events)
        assertEquals(Duration.ofSeconds(30), run.exportRemaining)
        assertEquals(2, run.report?.candidates?.candidates?.size)
        assertTrue(run.report?.candidates?.candidates.orEmpty().all { it.estimatedBytes == null })
    }

    @Test
    fun `interruption during size preparation preserves completed and unknown estimates`() {
        val run = Harness()
        run.onSize = { candidate ->
            if (candidate.id == "records_group_created_at") problem(ErrorCode.DB_CONNECTION_ERROR)
        }
        val result = run.execute()

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(listOf(StopReason.CONNECTION_LOST), result.diagnostics.stopReasons)
        assertEquals(listOf(262_144L, null), run.report?.candidates?.candidates?.map { it.estimatedBytes })
        assertFalse("optimize" in run.events)
        assertEquals(1, run.closeCalls)
    }

    @Test
    fun `local hypothesis failure retains the full report entry but excludes it from search`() {
        val run = Harness()
        val rejected = run.shortlist.first().copy(
            ddl = "CREATE INDEX ON public.records USING btree (created_at DESC NULLS FIRST)",
        )
        run.shortlist = listOf(rejected) + run.shortlist.drop(1)
        run.onSize = { candidate ->
            if (candidate.id == "records_created_at") problem(ErrorCode.HYPOTHETICAL_INDEX_ERROR)
        }
        run.greedyConfigurationIds = listOf("records_group_created_at")
        run.repairConfigurationIds = null
        run.robustIncumbentIds = listOf("records_group_created_at")
        run.meanIncumbentIds = listOf("records_group_created_at")
        val result = run.execute()

        assertEquals(RunStatus.COMPLETED, result.status)
        val reported = checkNotNull(run.report).candidates.candidates
        assertEquals(listOf("records_created_at", "records_group_created_at"), reported.map { it.id })
        assertEquals(rejected.copy(estimatedBytes = null, budgetedBytes = null, writeUnitsPerHour = null),
            reported.first())
        assertEquals(ContractFixtures.candidates().last(), reported.last())
        assertEquals(listOf("records_group_created_at"), run.optimizedCandidateIds)
        assertEquals(listOf(emptyList(), listOf("records_group_created_at")), run.evaluatedSets)
        assertEquals(listOf(WarningCode.CANDIDATE_REJECTED), result.diagnostics.warnings.map { it.code })
        assertEquals("records_created_at", result.diagnostics.candidateDecisions.single().candidateId)
        assertEquals(CandidateReason.HYPOTHETICAL_INDEX_ERROR, result.diagnostics.candidateDecisions.single().reason)
    }

    @Test
    fun `all rejected hypotheses remain reportable and skip search`() {
        val run = Harness()
        run.onSize = { problem(ErrorCode.HYPOTHETICAL_INDEX_ERROR) }
        val result = run.execute()

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf(StopReason.NO_CANDIDATES), result.diagnostics.stopReasons)
        assertFalse("optimize" in run.events)
        assertEquals(listOf(emptyList<String>()), run.evaluatedSets)
        assertEquals(run.shortlist.map {
            it.copy(estimatedBytes = null, budgetedBytes = null, writeUnitsPerHour = null)
        }, run.report?.candidates?.candidates)
        assertEquals(run.shortlist.map { it.id }, result.diagnostics.candidateDecisions.map { it.candidateId })
        assertTrue(result.diagnostics.candidateDecisions.all { it.reason == CandidateReason.HYPOTHETICAL_INDEX_ERROR })
        assertEquals(2, result.diagnostics.warnings.count { it.code == WarningCode.CANDIDATE_REJECTED })
    }

    @Test
    fun `interruption after a rejected hypothesis retains both its entry and the unfinished suffix`() {
        val run = Harness()
        run.onSize = { candidate ->
            problem(if (candidate.id == "records_created_at") ErrorCode.HYPOTHETICAL_INDEX_ERROR
                else ErrorCode.DB_CONNECTION_ERROR)
        }
        val result = run.execute()

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(listOf(StopReason.CONNECTION_LOST), result.diagnostics.stopReasons)
        assertEquals(run.shortlist.map {
            it.copy(estimatedBytes = null, budgetedBytes = null, writeUnitsPerHour = null)
        }, run.report?.candidates?.candidates)
        assertEquals("records_created_at", result.diagnostics.candidateDecisions.single().candidateId)
        assertEquals(CandidateReason.HYPOTHETICAL_INDEX_ERROR, result.diagnostics.candidateDecisions.single().reason)
        assertEquals(listOf(WarningCode.CANDIDATE_REJECTED), result.diagnostics.warnings.map { it.code })
        assertFalse("optimize" in run.events)
        assertEquals(1, run.closeCalls)
    }

    @Test
    fun `shortlist order does not determine scripted search stages or incumbents`() {
        val run = Harness()
        run.shortlist = run.shortlist.reversed()
        val result = run.execute()

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf(emptyList(), listOf("records_created_at"), listOf("records_group_created_at")),
            run.evaluatedSets)
        assertEquals(ContractFixtures.robustConfiguration(), result.bestRobust)
        assertEquals(ContractFixtures.meanConfiguration(), result.bestMean)
        assertEquals(AnalysisPhase.entries.toList(), run.progress.map { it.phase }.distinct())
        assertEquals(RunStatistics(6, 3, 3, 1, 1), result.diagnostics.statistics)
    }

    @Test
    fun `recoverable search failure exports both checkpoint incumbents without reevaluation`() {
        for (objective in Objective.entries) {
            val run = Harness(ContractFixtures.workload(objective))
            run.afterCheckpoint = { problem(ErrorCode.EXPLAIN_LIMIT) }
            val result = run.execute()

            assertEquals(RunStatus.PARTIAL, result.status)
            assertEquals(ContractFixtures.robustConfiguration(), result.bestRobust)
            assertEquals(ContractFixtures.meanConfiguration(), result.bestMean)
            assertEquals(if (objective == Objective.ROBUST) result.bestRobust else result.bestMean, result.selected)
            assertEquals(listOf(StopReason.EXPLAIN_LIMIT), result.diagnostics.stopReasons)
            assertEquals(3, run.evaluatedSets.size)
            assertEquals(6, result.diagnostics.statistics.explainAttempts)
            assertSame(result, run.report?.result)
        }
    }

    @Test
    fun `incomplete checkpoint never replaces the previously saved complete incumbents`() {
        val run = Harness()
        run.publishInvalidCheckpoint = true
        val result = run.execute()

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(listOf(StopReason.EVALUATION_FAILED), result.diagnostics.stopReasons)
        assertEquals(ContractFixtures.robustConfiguration(), result.bestRobust)
        assertEquals(ContractFixtures.meanConfiguration(), result.bestMean)
        assertEquals(ErrorCode.EXPLAIN_FAILED, result.diagnostics.errors.single().code)
        assertEquals(3, run.evaluatedSets.size)
    }

    @Test
    fun `returned search failure uses saved checkpoint only for recoverable errors`() {
        for (code in listOf(ErrorCode.EXPLAIN_LIMIT, ErrorCode.NUMERIC_ERROR)) {
            val run = Harness()
            run.returnedSearchFailure = code
            val result = run.execute()

            assertEquals(ContractFixtures.baselineEvaluation(), result.baseline)
            assertEquals(listOf(code), result.diagnostics.errors.map { it.code })
            if (code == ErrorCode.EXPLAIN_LIMIT) {
                assertEquals(RunStatus.PARTIAL, result.status)
                assertEquals(listOf(StopReason.EXPLAIN_LIMIT), result.diagnostics.stopReasons)
                assertEquals(ContractFixtures.robustConfiguration(), result.bestRobust)
                assertEquals(ContractFixtures.meanConfiguration(), result.bestMean)
            } else {
                assertEquals(RunStatus.FAILED, result.status)
                assertEquals(ErrorCode.NUMERIC_ERROR, (result as SearchResult.Failed).error.code)
                assertNull(result.selected)
            }
            assertEquals(1, run.closeCalls)
            assertSame(result, run.report?.result)
        }
    }

    @Test
    fun `catalog warnings survive both completed and partial analysis`() {
        for (partial in listOf(false, true)) {
            val run = Harness()
            val warning = AnalysisWarning(WarningCode.MISSING_STATISTICS, "Synthetic missing statistics")
            run.catalogWarnings = listOf(warning)
            if (partial) run.afterCheckpoint = { problem(ErrorCode.REPAIR_LIMIT) }
            val result = run.execute()

            assertEquals(if (partial) RunStatus.PARTIAL else RunStatus.COMPLETED, result.status)
            assertEquals(listOf(warning), result.diagnostics.warnings)
            assertEquals(listOf(warning), run.report?.result?.diagnostics?.warnings)
        }
    }

    @Test
    fun `numeric catalog and input failures stay failed even after a valid baseline`() {
        for (code in listOf(ErrorCode.NUMERIC_ERROR, ErrorCode.CATALOG_ERROR, ErrorCode.INVALID_INPUT)) {
            val run = Harness()
            run.onGenerate = { problem(code) }
            val result = run.execute()

            assertEquals(RunStatus.FAILED, result.status)
            assertEquals(code, (result as SearchResult.Failed).error.code)
            assertNotNull(result.baseline)
            assertNull(result.selected)
            assertNull(result.bestRobust)
            assertNull(result.bestMean)
            assertEquals(1, run.closeCalls)
        }
    }

    @Test
    fun `cancellation closes session and preserves baseline without hiding the primary error`() {
        val run = Harness()
        run.onGenerate = { run.cancelRegistered?.invoke() }
        run.failClose = true
        val result = run.execute()

        assertEquals(RunStatus.PARTIAL, result.status)
        assertTrue(run.context.isCancelled)
        assertTrue(run.cancelCalls >= 1)
        assertEquals(1, run.closeCalls)
        assertEquals(1, run.unregisterCalls)
        assertEquals(listOf(StopReason.CANCELLED), result.diagnostics.stopReasons)
        assertEquals(listOf(ErrorCode.CANCELLED, ErrorCode.DB_CONNECTION_ERROR), result.diagnostics.errors.map { it.code })
        assertFalse("optimize" in run.events)
        assertSame(result, run.report?.result)
    }

    @Test
    fun `driver error following cancellation retains cancellation reason and driver diagnostic`() {
        val run = Harness()
        run.onGenerate = {
            run.cancelRegistered?.invoke()
            problem(ErrorCode.DB_CONNECTION_ERROR)
        }
        val result = run.execute()

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(listOf(StopReason.CANCELLED), result.diagnostics.stopReasons)
        assertEquals(setOf(ErrorCode.CANCELLED, ErrorCode.DB_CONNECTION_ERROR),
            result.diagnostics.errors.map { it.code }.toSet())
        assertEquals(ContractFixtures.baselineEvaluation(), result.baseline)
        assertEquals(emptyList<String>(), result.selected?.evaluation?.candidateIds)
        assertTrue(run.cancelCalls >= 1)
        assertEquals(1, run.closeCalls)
        assertEquals(1, run.unregisterCalls)
        assertFalse("optimize" in run.events)
    }

    @Test
    fun `cleanup failure does not replace a fatal primary analysis failure`() {
        val run = Harness()
        run.onGenerate = { problem(ErrorCode.NUMERIC_ERROR) }
        run.failClose = true
        val result = run.execute()

        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(ErrorCode.NUMERIC_ERROR, (result as SearchResult.Failed).error.code)
        assertEquals(listOf(ErrorCode.NUMERIC_ERROR, ErrorCode.DB_CONNECTION_ERROR), result.diagnostics.errors.map { it.code })
        assertEquals(1, run.unregisterCalls)
    }

    @Test
    fun `close failure after successful search exports both saved incumbents as partial`() {
        val run = Harness()
        run.failClose = true
        val result = run.execute()

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(listOf(StopReason.SEARCH_COMPLETE, StopReason.CONNECTION_LOST), result.diagnostics.stopReasons)
        assertEquals(ContractFixtures.robustConfiguration(), result.bestRobust)
        assertEquals(ContractFixtures.meanConfiguration(), result.bestMean)
        assertEquals(listOf(ErrorCode.DB_CONNECTION_ERROR), result.diagnostics.errors.map { it.code })
        assertEquals("Analysis session cleanup failed.", result.diagnostics.errors.single().message)
        assertEquals(1, run.closeCalls)
        assertEquals(1, run.unregisterCalls)
        assertEquals(listOf(emptyList(), listOf("records_created_at"), listOf("records_group_created_at")),
            run.evaluatedSets)
        assertEquals(6, result.diagnostics.statistics.explainAttempts)
        assertEquals(3, result.diagnostics.statistics.completedEvaluations)
        assertSame(result, run.report?.result)
        assertEquals(listOf("close", "unregister", "report", "export"), run.events.takeLast(4))
    }

    @Test
    fun `report inconsistent with this run is rejected before exporter is called`() {
        val corruptions = listOf<Pair<String, (AnalysisReport) -> AnalysisReport>>(
            "runId" to { report -> report.copy(runId = "different-run") },
            "inputHash" to { report -> report.copy(inputHash = "b".repeat(64)) },
            "input" to { report -> report.copy(input = checkNotNull(report.input).copy(objective = Objective.MEAN)) },
            "result" to { report -> report.copy(result = SearchResult.Failed(
                report.result.selectedObjective, AnalysisError(ErrorCode.EXPLAIN_FAILED, "Different saved result"),
            )) },
        )
        for ((field, corrupt) in corruptions) {
            val run = Harness()
            run.transformReport = corrupt
            val error = assertThrows(AnalysisException::class.java, { run.execute() }, field).error

            assertEquals(ErrorCode.EXPORT_FAILED, error.code, field)
            assertFalse("export" in run.events, field)
            assertNull(run.report, field)
            assertEquals(1, run.closeCalls, field)
            assertFalse(AnalysisPhase.REPORT in run.context.completedPhases(), field)
        }
    }

    @Test
    fun `expired overall budget still permits minimal export from the last full checkpoint`() {
        val run = Harness()
        run.afterCheckpoint = { run.advance(90) }
        val result = run.execute()

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(listOf(StopReason.SEARCH_COMPLETE, StopReason.DEADLINE_EXCEEDED), result.diagnostics.stopReasons)
        assertEquals(ContractFixtures.robustConfiguration(), result.bestRobust)
        assertEquals(ContractFixtures.meanConfiguration(), result.bestMean)
        assertEquals(Duration.ZERO, run.exportRemaining)
        assertEquals(1, run.cancelCalls)
        assertEquals(1, run.closeCalls)
        assertEquals(3, run.evaluatedSets.size)
    }

    @Test
    fun `export failures are typed and do not leak external exception messages`() {
        for (failure in listOf(
            IOException("postgresql://user:secret@host/database"),
            AnalysisException(AnalysisError(ErrorCode.DEADLINE_EXCEEDED, "external details")),
            AnalysisException(AnalysisError(ErrorCode.OUTPUT_DIRECTORY_NOT_EMPTY, "Output is not empty.")),
        )) {
            val run = Harness()
            run.exportFailure = failure
            val error = assertThrows(AnalysisException::class.java) { run.execute() }.error

            assertEquals(ErrorCode.EXPORT_FAILED, error.code)
            assertFalse(error.message.contains("secret"))
            assertFalse(error.message.contains("external details"))
            assertEquals(1, run.closeCalls)
            assertFalse(AnalysisPhase.REPORT in run.context.completedPhases())
        }
    }

    @Test
    fun `CLI returns export failure when output becomes occupied after validation`(@TempDir directory: Path) {
        val output = Files.createDirectory(directory.resolve("output"))
        val occupiedFile = output.resolve("other-run.txt")
        var harness: Harness? = null
        val cli = IndexBudgetCli(WorkloadReader(), analysisRunner = AnalysisRunner { input, path, context ->
            val run = Harness(input, context, path)
            harness = run
            run.afterCheckpoint = { Files.writeString(occupiedFile, "Another run's artifact") }
            run.beforeExport = { OutputDirectoryValidator().validate(path) }
            run.createPipeline().run(input, path, context)
        })
        val err = StringWriter()
        val exit = cli.execute(arrayOf("analyze", "--workload", "examples/workload-v2.json",
            "--output-dir", output.toString()), PrintWriter(StringWriter()), PrintWriter(err))

        val run = checkNotNull(harness)
        assertEquals(1, exit)
        assertTrue(err.toString().contains("EXPORT_FAILED:"))
        assertFalse(err.toString().contains("OUTPUT_DIRECTORY_NOT_EMPTY:"))
        assertEquals(3, run.evaluatedSets.size)
        assertEquals(1, run.closeCalls)
        assertTrue("export" in run.events)
        assertFalse(AnalysisPhase.REPORT in run.context.completedPhases())
        assertEquals("Another run's artifact", Files.readString(occupiedFile))
        assertEquals(1L, Files.list(output).use { it.count() })
    }

    private class Harness(
        val input: WorkloadInput = ContractFixtures.workload(),
        suppliedContext: RunContext? = null,
        private val outputDirectory: Path = Path.of("reports/test-run"),
        private val suppliedMetrics: MetricsCalculator? = null,
    ) {
        private var now = 0L
        val progress = mutableListOf<RunProgress>()
        val context = suppliedContext ?: RunContext(clock = NanoClock { now }, onProgress = { progress += it }).apply {
            recordInputHash("a".repeat(64))
        }
        val events = mutableListOf<String>()
        val evaluatedSets = mutableListOf<List<String>>()
        var optimizedCandidateIds = emptyList<String>()
        var openCalls = 0
        var closeCalls = 0
        var cancelCalls = 0
        var unregisterCalls = 0
        var report: AnalysisReport? = null
        var exportRemaining: Duration? = null
        var cancelRegistered: (() -> Unit)? = null
        var failClose = false
        var publishInvalidCheckpoint = false
        var returnedSearchFailure: ErrorCode? = null
        var catalogWarnings = emptyList<AnalysisWarning>()
        var baselineAction: (() -> EvaluationResult)? = null
        var onParse: () -> Unit = {}
        var onGenerate: () -> Unit = {}
        var afterBaseline: () -> Unit = {}
        var onSize: (IndexCandidate) -> Unit = {}
        var afterCheckpoint: () -> Unit = {}
        var beforeExport: () -> Unit = {}
        var exportFailure: Exception? = null
        var transformReport: (AnalysisReport) -> AnalysisReport = { it }
        var shortlist = ContractFixtures.candidates()
        var greedyConfigurationIds = listOf("records_created_at")
        var repairConfigurationIds: List<String>? = listOf("records_group_created_at")
        var robustIncumbentIds = listOf("records_created_at")
        var meanIncumbentIds = listOf("records_group_created_at")
        private var closed = false
        private val expectedUsageByCandidateId = mapOf(
            "records_created_at" to BudgetUsage(262_144L, 314_573L, 4100.0, 1),
            "records_group_created_at" to BudgetUsage(524_288L, 629_146L, 8200.0, 1),
        )
        private val configurationsByIds = mapOf(
            emptyList<String>() to ContractFixtures.baselineConfiguration(),
            listOf("records_created_at") to ContractFixtures.robustConfiguration(),
            listOf("records_group_created_at") to ContractFixtures.meanConfiguration(),
        )
        val scriptedEvaluations = configurationsByIds.mapValues { it.value.evaluation }.toMutableMap()

        fun advance(seconds: Long) { now += Duration.ofSeconds(seconds).toNanos() }

        private fun observe(event: String, actualContext: RunContext) {
            assertSame(context, actualContext)
            events += event
        }

        private val session = object : AnalysisSession {
            override fun readCatalog(tables: Set<TableName>, context: RunContext): CatalogSnapshot {
                observe("catalog", context)
                assertEquals(setOf(TableName("records", "public")), tables)
                return ContractFixtures.catalog().copy(warnings = catalogWarnings)
            }

            override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                observe("size:${candidate.id}", context)
                assertFalse(closed)
                onSize(candidate)
                return expectedUsageByCandidateId.getValue(candidate.id).estimatedBytes
            }

            override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
                                  context: RunContext): EvaluationResult {
                observe("evaluate", context)
                assertFalse(closed)
                assertSame(input, workload.input)
                val configurationIds = candidates.map { it.id }.sorted()
                evaluatedSets += configurationIds
                context.reserveEvaluationAttempt()
                input.queries.forEach { context.reserveExplainAttempt() }
                val scriptedEvaluation = scriptedEvaluations.getValue(configurationIds)
                val evaluation = if (candidates.isEmpty()) {
                    baselineAction?.invoke() ?: scriptedEvaluation
                } else scriptedEvaluation
                if (evaluation.costByQueryId.keys == input.queries.map { it.id }.toSet()) {
                    context.recordCompletedEvaluation()
                }
                if (candidates.isEmpty()) afterBaseline()
                return evaluation
            }

            override fun cancel() { cancelCalls++; events += "cancel" }
            override fun close() {
                closeCalls++
                closed = true
                events += "close"
                if (failClose) throw IOException("private driver cleanup details")
            }
        }

        private val metrics: MetricsCalculator = suppliedMetrics ?: object : MetricsCalculator {
            override fun assessBudget(candidates: List<IndexCandidate>, workload: WorkloadInput): BudgetAssessment {
                assertSame(input, workload)
                val usage = if (candidates.isEmpty()) {
                    BudgetUsage(0, 0, if (workload.tableWriteRates == null) null else 0.0, 0)
                } else {
                    val candidate = candidates.single()
                    val expected = expectedUsageByCandidateId.getValue(candidate.id)
                    assertEquals(expected.estimatedBytes, candidate.estimatedBytes, candidate.id)
                    expected
                }
                return BudgetAssessment(usage)
            }

            override fun calculate(workload: WorkloadInput, baseline: EvaluationResult,
                                   evaluation: EvaluationResult, usage: BudgetUsage): SetMetrics {
                assertSame(input, workload)
                val metrics = configurationsByIds.getValue(evaluation.candidateIds).metrics
                return metrics.copy(budgetUsage = usage)
            }
        }

        private val comparator = object : ObjectiveComparator {
            override fun compare(left: EvaluatedConfiguration, right: EvaluatedConfiguration, objective: Objective): Int =
                error("Orchestration must not compare configurations")
            override fun improvesPrimaryMetrics(proposed: SetMetrics, current: SetMetrics, objective: Objective): Boolean =
                error("Orchestration must not calculate search movement")
        }

        private val optimizer = object : Optimizer {
            override fun optimize(workload: ResolvedWorkload, candidates: CandidateBatch, baseline: EvaluationResult,
                                  evaluator: ConfigurationEvaluator, metrics: MetricsCalculator,
                                  comparator: ObjectiveComparator, context: RunContext,
                                  onCheckpoint: (SearchCheckpoint) -> Unit): SearchResult {
                observe("optimize", context)
                optimizedCandidateIds = candidates.candidates.map { it.id }
                assertSame(session, evaluator)
                assertSame(this@Harness.metrics, metrics)
                assertSame(this@Harness.comparator, comparator)
                for (candidate in candidates.candidates) {
                    val expected = expectedUsageByCandidateId.getValue(candidate.id)
                    assertEquals(expected.estimatedBytes, candidate.estimatedBytes, candidate.id)
                    assertEquals(expected.budgetedBytes, candidate.budgetedBytes, candidate.id)
                    assertEquals(expected.writeUnitsPerHour, candidate.writeUnitsPerHour, candidate.id)
                }
                val candidatesById = candidates.candidates.associateBy { it.id }
                val saved = mutableMapOf<List<String>, EvaluatedConfiguration>()
                val rejections = mutableListOf<AnalysisError>()
                fun evaluateScriptedConfiguration(ids: List<String>) {
                    val selected = ids.map { candidatesById.getValue(it) }
                    val budget = metrics.assessBudget(selected, input)
                    check(budget.admissible)
                    val evaluation = evaluator.evaluate(selected, workload, context)
                    when (val assessment = metrics.assessEvaluation(input, baseline, evaluation, budget.usage)) {
                        is EvaluationAssessment.Accepted ->
                            saved[ids] = EvaluatedConfiguration(evaluation, assessment.metrics)
                        is EvaluationAssessment.Rejected -> rejections += assessment.violations
                    }
                }

                assertEquals(AnalysisPhase.GREEDY, context.phase)
                evaluateScriptedConfiguration(greedyConfigurationIds)
                context.completePhase()
                repairConfigurationIds?.let { ids ->
                    context.enterPhase(AnalysisPhase.REPAIR)
                    context.recordCacheHit()
                    context.reserveRepairAttempt()
                    evaluateScriptedConfiguration(ids)
                    context.completePhase()
                }
                val robust = saved.getValue(robustIncumbentIds)
                val mean = saved.getValue(meanIncumbentIds)
                onCheckpoint(SearchCheckpoint(robust, mean, SearchDiagnostics(errors = rejections.toList())))
                if (publishInvalidCheckpoint) {
                    val invalid = mean.evaluation.copy(
                        costByQueryId = mean.evaluation.costByQueryId.filterKeys { it == "records_by_group" },
                        plansByQueryId = mean.evaluation.plansByQueryId.filterKeys { it == "records_by_group" },
                    )
                    onCheckpoint(SearchCheckpoint(robust, mean.copy(evaluation = invalid)))
                }
                afterCheckpoint()
                returnedSearchFailure?.let { code ->
                    // Search may return only its error; the application already owns the baseline
                    // and both complete incumbents published before the failed operation.
                    return SearchResult.Failed(input.objective, AnalysisError(code, "Synthetic search failure"))
                }
                return SearchResult.Completed(input.objective, baseline, robust, mean,
                    SearchDiagnostics(stopReasons = listOf(StopReason.SEARCH_COMPLETE), errors = rejections.toList()))
            }
        }

        fun execute(): SearchResult {
            context.enterPhase(AnalysisPhase.VALIDATION)
            context.completePhase()
            return createPipeline().run(input, outputDirectory, context)
        }

        fun createPipeline(): AnalysisPipeline {
            return AnalysisPipeline(
                sqlAnalyzer = object : SqlAnalyzer {
                    override fun parse(input: WorkloadInput, context: RunContext): ParsedWorkload {
                        observe("parse", context)
                        onParse()
                        return object : ParsedWorkload {
                            override val input = this@Harness.input
                            override val referencedTables = setOf(TableName("records", "public"))
                        }
                    }
                    override fun resolve(parsed: ParsedWorkload, catalog: CatalogSnapshot, context: RunContext): ResolvedWorkload {
                        observe("resolve", context)
                        return ContractFixtures.resolvedWorkload().copy(input = input)
                    }
                },
                sessionFactory = object : AnalysisSessionFactory {
                    override fun open(context: RunContext): AnalysisSession {
                        observe("open", context)
                        openCalls++
                        return session
                    }
                },
                candidateGenerator = object : CandidateGenerator {
                    override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext): CandidateBatch {
                        observe("generate", context)
                        onGenerate()
                        return CandidateBatch(shortlist.map {
                            it.copy(estimatedBytes = null, budgetedBytes = null, writeUnitsPerHour = null)
                        })
                    }
                },
                metrics = metrics, comparator = comparator, optimizer = optimizer,
                reportBuilder = ReportBuilder { workload, candidates, result, reportContext ->
                    observe("report", reportContext)
                    assertTrue(closed || openCalls == 0)
                    transformReport(AnalysisReport(context.runId, context.inputHash, context.elapsedTime().toMillis(),
                        EnvironmentInfo("test", "test"), result, workload, candidates))
                },
                reportExporter = ReportExporter { built, output, exportContext ->
                    observe("export", exportContext)
                    assertEquals(outputDirectory, output)
                    assertTrue(closed || openCalls == 0)
                    report = built
                    exportRemaining = context.remainingTime()
                    beforeExport()
                    exportFailure?.let { throw it }
                },
                shutdownCancellation = ShutdownCancellation { cancel ->
                    cancelRegistered = cancel
                    AutoCloseable { unregisterCalls++; events += "unregister" }
                },
            )
        }
    }

    companion object {
        private fun problem(code: ErrorCode): Nothing = throw AnalysisException(AnalysisError(code, "Synthetic failure"))
    }
}
