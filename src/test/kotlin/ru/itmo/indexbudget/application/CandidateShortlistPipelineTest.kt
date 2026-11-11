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
            assertInstanceOf(JdbcAnalysisSessionFactory::class.java, context.getBean(AnalysisSessionFactory::class.java))
            assertInstanceOf(MetricsCalculatorImpl::class.java, context.getBean(MetricsCalculator::class.java))
            assertInstanceOf(ObjectiveComparatorImpl::class.java, context.getBean(ObjectiveComparator::class.java))
            assertInstanceOf(LimitedRepairOptimizer::class.java, context.getBean(Optimizer::class.java))
            assertInstanceOf(UnavailableAnalysisRunner::class.java, context.getBean(AnalysisRunner::class.java))
        }
    }

    @Test
    fun `preparation refills two rejected candidates and stops after sixteen admissible sizes`(@TempDir output: Path) {
        val scenario = CandidateGenerationFixtures.wideAllFilters()
        val input = scenario.workload.input.copy(constraints = Constraints(1000, null))
        val catalog = scenario.catalog.withSqlSemantics()
        val sized = mutableListOf<String>()
        var generatedQueue = emptyList<IndexCandidate>()
        var generated = 0
        var evaluated = 0
        var closed = false
        var exported: AnalysisReport? = null
        val session = object : AnalysisSession {
            override fun readCatalog(tables: Set<TableName>, context: RunContext) = catalog
            override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload, context: RunContext): EvaluationResult {
                assertTrue(candidates.isEmpty(), "Only the synthetic baseline is evaluated in this contract test")
                evaluated++
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
                // The first two attempts fail locally or exceed storage; positions 3–18 fill all sixteen seats.
                assertEquals(16, candidates.candidates.size)
                assertEquals(generatedQueue.drop(2).take(16).map { it.id }, candidates.candidates.map { it.id })
                assertTrue(candidates.candidates.all { metrics.assessBudget(listOf(it), input).admissible })
                assertTrue(candidates.candidates.all { it.estimatedBytes == 10L && it.budgetedBytes == 12L })
                assertTrue(candidates.reserve.isEmpty())
                val empty = EvaluatedConfiguration(baseline, metrics.calculate(input, baseline, baseline,
                    metrics.assessBudget(emptyList(), input).usage))
                val diagnostics = SearchDiagnostics()
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
                    return ProfileAwareCandidateGenerator().generate(workload, catalog, context).also {
                        generatedQueue = it.candidates + it.reserve
                    }
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
        assertEquals(1, evaluated)
        assertEquals(32, generatedQueue.size)
        assertEquals(18, sized.size)
        assertEquals(18, sized.toSet().size)
        assertEquals(generatedQueue.take(18).map { it.id }, sized)
        val report = checkNotNull(exported)
        assertEquals(generatedQueue.map { it.id }, report.candidates.candidates.map { it.id })
        assertTrue(report.candidates.reserve.isEmpty())
        assertNull(report.candidates.candidates.first().estimatedBytes)
        assertNull(report.candidates.candidates.first().budgetedBytes)
        assertEquals(2000L, report.candidates.candidates[1].estimatedBytes)
        assertEquals(2400L, report.candidates.candidates[1].budgetedBytes)
        assertTrue(report.candidates.candidates.drop(18).all {
            it.estimatedBytes == null && it.budgetedBytes == null && it.writeUnitsPerHour == null
        })
        assertEquals(CandidatePreparationStatistics(18, 18, 1, 1, 2, 16,
            CandidatePreparationStopReason.SHORTLIST_FULL), report.candidates.diagnostics.preparation)
        assertEquals(setOf(CandidateReason.COLUMN_SHORTLIST_TRUNCATED, CandidateReason.LOCAL_CANDIDATE_LIMIT,
            CandidateReason.HYPOTHETICAL_INDEX_ERROR, CandidateReason.STORAGE_BUDGET,
            CandidateReason.NOT_EVALUATED_DUE_TO_LIMIT), report.result.diagnostics.candidateDecisions.map { it.reason }.toSet())
        val decisions = report.result.diagnostics.candidateDecisions
        assertEquals(sized.first(), decisions.single { it.reason == CandidateReason.HYPOTHETICAL_INDEX_ERROR }.candidateId)
        val budgetRejection = decisions.single { it.reason == CandidateReason.STORAGE_BUDGET }
        assertEquals(sized[1], budgetRejection.candidateId)
        assertEquals(AnalysisPhase.CANDIDATES, budgetRejection.phase)
        assertEquals(generatedQueue.drop(18).map { it.id }.toSet(), decisions.filter {
            it.reason == CandidateReason.NOT_EVALUATED_DUE_TO_LIMIT
        }.map { it.candidateId }.toSet())
        assertEquals("true", report.candidates.diagnostics.warnings.single {
            it.code == WarningCode.GLOBAL_CANDIDATE_LIMIT
        }.details["refill_after_budget_filter"])
    }
}
