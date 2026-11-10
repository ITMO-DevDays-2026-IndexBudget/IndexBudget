package ru.itmo.indexbudget.application

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.CandidateGenerationFixtures
import ru.itmo.indexbudget.fixtures.withSqlSemantics
import ru.itmo.indexbudget.optimizer.*
import ru.itmo.indexbudget.postgres.*
import ru.itmo.indexbudget.report.*
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer

class CandidateShortlistPipelineTest {
    @Test
    fun `production generator is registered without enabling incomplete pipeline`() {
        ApplicationContextRunner().withUserConfiguration(AnalysisConfiguration::class.java).run { context ->
            assertInstanceOf(ProfileAwareCandidateGenerator::class.java, context.getBean(CandidateGenerator::class.java))
            assertInstanceOf(UnavailableAnalysisRunner::class.java, context.getBean(AnalysisRunner::class.java))
        }
    }

    @Test
    fun `only final sixteen get sizes and hypothesis or budget rejection never refills shortlist`(@TempDir output: Path) {
        val scenario = CandidateGenerationFixtures.wideAllFilters()
        val input = scenario.workload.input.copy(constraints = Constraints(1000, null))
        val catalog = scenario.catalog.withSqlSemantics()
        val sized = mutableListOf<String>()
        var generated = 0
        var closed = false
        var exported: AnalysisReport? = null
        val session = object : AnalysisSession {
            override fun readCatalog(tables: Set<TableName>, context: RunContext) = catalog
            override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload, context: RunContext): EvaluationResult {
                assertTrue(candidates.isEmpty(), "Only the synthetic baseline is evaluated in this contract test")
                return EvaluationResult(emptyList(), input.queries.associate { it.id to 100.0 },
                    input.queries.associate { it.id to ExplainPlan("{}") })
            }
            override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                assertTrue(generated == 1 && !closed)
                assertNull(candidate.estimatedBytes)
                sized += candidate.id
                if (sized.size == 1) throw AnalysisException(AnalysisError(ErrorCode.HYPOTHETICAL_INDEX_ERROR, "Test failure"))
                return if (sized.size == 2) 2000 else 10
            }
            override fun cancel() = Unit
            override fun close() { closed = true }
        }
        val optimizer = object : Optimizer {
            override fun optimize(workload: ResolvedWorkload, candidates: CandidateBatch, baseline: EvaluationResult,
                                  evaluator: ConfigurationEvaluator, metrics: MetricsCalculator,
                                  comparator: ObjectiveComparator, context: RunContext,
                                  onCheckpoint: (SearchCheckpoint) -> Unit): SearchResult {
                assertEquals(15, candidates.candidates.size, "Hypothesis failure is excluded, not refilled")
                val rejected = candidates.candidates.filter { !metrics.assessBudget(listOf(it), input).admissible }
                assertEquals(listOf(sized[1]), rejected.map { it.id })
                val empty = EvaluatedConfiguration(baseline, metrics.calculate(input, baseline, baseline,
                    metrics.assessBudget(emptyList(), input).usage))
                val diagnostics = SearchDiagnostics(candidateDecisions = listOf(CandidateDecision(
                    CandidateReason.STORAGE_BUDGET, candidateId = rejected.single().id, phase = AnalysisPhase.GREEDY)))
                onCheckpoint(SearchCheckpoint(empty, empty, diagnostics))
                context.completePhase()
                return SearchResult.Completed(input.objective, baseline, empty, empty, diagnostics)
            }
        }
        val pipeline = AnalysisPipeline(
            sqlAnalyzer = JSqlParserSqlAnalyzer(),
            sessionFactory = object : AnalysisSessionFactory { override fun open(context: RunContext) = session },
            candidateGenerator = object : CandidateGenerator {
                override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext): CandidateBatch {
                    generated++
                    return ProfileAwareCandidateGenerator().generate(workload, catalog, context)
                }
            },
            metrics = MetricsCalculatorImpl(), comparator = ObjectiveComparatorImpl(), optimizer = optimizer,
            reportBuilder = ReportBuilder { workload, candidates, result, context ->
                assertTrue(closed)
                AnalysisReport(context.runId, context.inputHash, 0, EnvironmentInfo("test", "test"), result,
                    input = workload, candidates = candidates)
            },
            reportExporter = ReportExporter { report, _, _ -> exported = report },
            shutdownCancellation = ShutdownCancellation { AutoCloseable {} },
        )
        assertEquals(RunStatus.COMPLETED, pipeline.run(input, output, RunContext()).status)
        assertEquals(1, generated)
        assertEquals(16, sized.size)
        assertEquals(16, sized.toSet().size)
        val report = checkNotNull(exported)
        assertEquals(sized, report.candidates.candidates.map { it.id })
        assertNull(report.candidates.candidates.first().estimatedBytes)
        assertEquals(setOf(CandidateReason.COLUMN_SHORTLIST_TRUNCATED, CandidateReason.LOCAL_CANDIDATE_LIMIT,
            CandidateReason.GLOBAL_CANDIDATE_LIMIT, CandidateReason.HYPOTHETICAL_INDEX_ERROR,
            CandidateReason.STORAGE_BUDGET), report.result.diagnostics.candidateDecisions.map { it.reason }.toSet())
        assertEquals("false", report.candidates.diagnostics.warnings.single {
            it.code == WarningCode.GLOBAL_CANDIDATE_LIMIT
        }.details["refill_after_budget_filter"])
    }
}
