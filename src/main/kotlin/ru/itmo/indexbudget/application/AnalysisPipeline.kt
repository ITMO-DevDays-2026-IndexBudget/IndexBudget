package ru.itmo.indexbudget.application

import ru.itmo.indexbudget.comparison.ManualIndexEvaluator
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.Optimizer
import ru.itmo.indexbudget.optimizer.SearchCheckpoint
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.report.ReportBuilder
import ru.itmo.indexbudget.report.ReportExporter
import ru.itmo.indexbudget.sql.SqlAnalyzer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Owns one invocation, but never duplicates the metrics, search or export algorithms. */
class AnalysisPipeline(
    private val sqlAnalyzer: SqlAnalyzer,
    private val sessionFactory: AnalysisSessionFactory,
    private val candidateGenerator: CandidateGenerator,
    private val metrics: MetricsCalculator,
    private val comparator: ObjectiveComparator,
    private val optimizer: Optimizer,
    private val reportBuilder: ReportBuilder,
    private val reportExporter: ReportExporter,
    private val resolvedValidator: ResolvedWorkloadValidator = ResolvedWorkloadValidator(),
    private val shutdownCancellation: ShutdownCancellation = JvmShutdownCancellation(),
) : AnalysisRunner, SeriesAnalysisRunner {
    override fun prepareSeriesPool(input: WorkloadInput, context: RunContext): SeriesPreparation {
        var session: AnalysisSession? = null
        val active = AtomicReference<AnalysisSession?>()
        val registration = shutdownCancellation.register {
            context.cancel()
            try { active.get()?.cancel() } catch (_: Exception) { }
        }
        var primary: Throwable? = null
        try {
            context.selectAnalysisMode(input.analysisMode)
            context.enterPhase(AnalysisPhase.INTROSPECTION)
            val parsed = checked(context) { sqlAnalyzer.parse(input, context) }
            session = sessionFactory.open(context)
            active.set(session)
            context.recordDatabaseVersions(session.databaseVersions)
            val catalog = checked(context) { session.readCatalog(parsed.referencedTables, context) }
            context.recordDatabaseVersions(checked(context) { session.readDatabaseVersions(context) })
            val resolved = checked(context) { sqlAnalyzer.resolve(parsed, catalog, context) }
            require(resolved.input == input && resolved.queries.map { it.id to it.sql } == input.queries.map { it.id to it.sql })
            resolvedValidator.validate(resolved, context)
            context.completePhase()
            context.startSearch()
            context.enterPhase(AnalysisPhase.BASELINE)
            val baseline = session.evaluate(emptyList(), resolved, context)
            requireCoverage(baseline, input.queries.map { it.id }.toSet())
            require(baseline.candidateIds.isEmpty())
            val assessment = metrics.assessEvaluation(input, baseline, baseline, metrics.assessBudget(emptyList(), input).usage)
            require(assessment is EvaluationAssessment.Accepted)
            context.checkDeadline()
            context.completePhase()
            context.enterPhase(AnalysisPhase.CANDIDATES)
            val batch = if (input.constraints.storageBudgetBytes == 0L || input.constraints.maxIndexes == 0 ||
                !assessment.searchRequired) CandidateBatch(emptyList()) else
                candidateGenerator.generate(resolved, catalog, context, baseline)
            val pool = CandidatePreparation(metrics).prepareFixedPool(batch, session, input, context)
            context.checkDeadline()
            context.completePhase()
            val conditions = checked(context) {
                SeriesConditionCapture.capture(catalog, baseline, session.databaseVersions, session.sourceFingerprint)
            }
            return SeriesPreparation(pool, conditions)
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            active.set(null)
            var cleanup: Throwable? = null
            try {
                if (context.isCancelled || context.remainingTime().isZero) session?.cancel()
            } catch (_: Exception) { cleanup = AnalysisException(cleanupError()) }
            try { session?.close() } catch (_: Exception) { cleanup = AnalysisException(cleanupError()) }
            try { registration.close() } catch (_: Exception) { cleanup = AnalysisException(cleanupError()) }
            cleanup?.let { if (primary != null) primary.addSuppressed(it) else throw it }
        }
    }

    override fun runSeriesPoint(input: WorkloadInput, outputDirectory: Path, context: RunContext,
        pool: PreparedCandidatePool): SeriesPointRun {
        var conditions: SeriesConditions? = null
        val result = runInternal(input, outputDirectory, context, pool) { conditions = it }
        return SeriesPointRun(result, conditions)
    }
    override fun run(input: WorkloadInput, outputDirectory: Path, context: RunContext): SearchResult =
        runInternal(input, outputDirectory, context, null)

    /** A series supplies its once-prepared pool; this invocation still owns its session and baseline. */
    fun runWithFixedPool(
        input: WorkloadInput,
        outputDirectory: Path,
        context: RunContext,
        pool: PreparedCandidatePool,
    ): SearchResult = runInternal(input, outputDirectory, context, pool)

    private fun runInternal(
        input: WorkloadInput,
        outputDirectory: Path,
        context: RunContext,
        fixedPool: PreparedCandidatePool?,
        onConditions: (SeriesConditions) -> Unit = {},
    ): SearchResult {
        var manual: ManualComparison? = input.manualIndexes?.let { ManualComparison(ManualComparisonStatus.NOT_EVALUATED) }
        val manualEvaluator = ManualIndexEvaluator()
        var baseline: EvaluationResult? = null
        var checkpoint: SearchCheckpoint? = null
        var candidateBatch = CandidateBatch(emptyList())
        var result: SearchResult? = null
        var failure: AnalysisError? = null
        val cleanupErrors = mutableListOf<AnalysisError>()
        val operationStopReasons = mutableListOf<StopReason>()
        val catalogWarnings = mutableListOf<AnalysisWarning>()
        val activeSession = AtomicReference<AnalysisSession?>()
        var registration: AutoCloseable? = null
        try {
            context.selectAnalysisMode(input.analysisMode)
            val fixedSelection = fixedPool?.let { pool ->
                CandidatePreparation(metrics).selectFixedPool(pool, input, context)
                    .also { candidateBatch = it.reportBatch }
            }
            registration = shutdownCancellation.register {
                context.cancel()
                // The adapter owns thread-safe cancellation of a pending statement/connection.
                try { activeSession.get()?.cancel() } catch (_: Exception) { /* close runs in finally */ }
            }
            context.enterPhase(AnalysisPhase.INTROSPECTION)
            val parsed = checked(context) { sqlAnalyzer.parse(input, context) }
            val session = sessionFactory.open(context)
            activeSession.set(session)
            context.recordDatabaseVersions(session.databaseVersions)
            context.checkDeadline()
            val catalog = checked(context) { session.readCatalog(parsed.referencedTables, context) }
            if (fixedPool != null) context.recordDatabaseVersions(checked(context) { session.readDatabaseVersions(context) })
            catalogWarnings += catalog.warnings
            val resolved = checked(context) { sqlAnalyzer.resolve(parsed, catalog, context) }
            require(resolved.input == input) { "Resolution must preserve the validated input" }
            require(resolved.queries.map { it.id } == input.queries.map { it.id }) {
                "Resolution must preserve every shared query"
            }
            require(resolved.queries.map { it.sql } == input.queries.map { it.sql }) {
                "Resolution must preserve original SQL"
            }
            resolvedValidator.validate(resolved, context)
            input.manualIndexes?.let { manual = manualEvaluator.prepare(it, catalog, resolved, context) }
            context.completePhase()

            context.startSearch()
            context.enterPhase(AnalysisPhase.BASELINE)
            val evaluatedBaseline = session.evaluate(emptyList(), resolved, context)
            requireCoverage(evaluatedBaseline, input.queries.map { it.id }.toSet())
            require(evaluatedBaseline.candidateIds.isEmpty()) { "Baseline must be the empty index set" }
            baseline = evaluatedBaseline
            if (fixedPool != null) onConditions(SeriesConditionCapture.capture(catalog, evaluatedBaseline, session.databaseVersions, session.sourceFingerprint))
            val budget = metrics.assessBudget(emptyList(), input)
            require(budget.admissible) { "The empty set must be admissible" }
            val baselineAssessment = metrics.assessEvaluation(input, evaluatedBaseline, evaluatedBaseline, budget.usage)
            require(baselineAssessment is EvaluationAssessment.Accepted) { "Baseline must be admissible" }
            val empty = EvaluatedConfiguration(evaluatedBaseline, baselineAssessment.metrics)
            checkpoint = SearchCheckpoint(empty, empty)
            // Save the full baseline before detecting a late return from an external operation.
            context.checkDeadline()
            context.completePhase()
            val earlyStop = when {
                input.constraints.storageBudgetBytes == 0L -> StopReason.ZERO_STORAGE_BUDGET
                input.constraints.maxIndexes == 0 -> StopReason.ZERO_MAX_INDEXES
                !baselineAssessment.searchRequired -> StopReason.ZERO_BASELINE
                else -> null
            }
            if (earlyStop != null) {
                result = SearchResult.Completed(input.objective, evaluatedBaseline, empty, empty,
                    SearchDiagnostics(stopReasons = listOf(earlyStop)))
            } else {
                context.enterPhase(AnalysisPhase.CANDIDATES)
                val preparation = CandidatePreparation(metrics)
                val prepared = if (fixedSelection == null) {
                    candidateBatch = candidateGenerator.generate(resolved, catalog, context, evaluatedBaseline)
                    preparation.prepare(candidateBatch, session, input, context) { candidateBatch = it }
                } else {
                    fixedSelection
                }
                val searchCandidates = prepared.searchBatch
                context.checkDeadline()
                context.completePhase()
                if (searchCandidates.candidates.isEmpty()) {
                    result = SearchResult.Completed(input.objective, evaluatedBaseline, empty, empty,
                        SearchDiagnostics(stopReasons = listOf(StopReason.NO_CANDIDATES)))
                } else {
                    context.enterPhase(AnalysisPhase.GREEDY)
                    result = optimizer.optimize(resolved, searchCandidates, evaluatedBaseline, session, metrics,
                        comparator, context) { saved ->
                        validateCheckpoint(saved, evaluatedBaseline)
                        checkpoint = saved
                    }
                    require(result.selectedObjective == input.objective) {
                        "Search must preserve the selected objective"
                    }
                    val returnedBaseline = result.baseline
                    if (result is SearchResult.Failed) {
                        require(returnedBaseline == null || returnedBaseline == evaluatedBaseline) {
                            "Failed search must omit the baseline or preserve it"
                        }
                    } else {
                        require(returnedBaseline == evaluatedBaseline) { "Search must preserve the baseline" }
                    }
                    val robust = result.bestRobust
                    val mean = result.bestMean
                    if (robust != null && mean != null) {
                        val saved = SearchCheckpoint(robust, mean, result.diagnostics)
                        validateCheckpoint(saved, evaluatedBaseline)
                        checkpoint = saved
                    }
                    if (result.status == RunStatus.COMPLETED) context.checkDeadline()
                }
            }
            if (manual != null && result.status != RunStatus.FAILED) {
                manual = manualEvaluator.evaluate(checkNotNull(manual), resolved, evaluatedBaseline,
                    session, metrics, context) { manual = it }
            }
        } catch (error: AnalysisException) {
            failure = error.error
            // The adapter preserves the primary failure and attaches safe typed cleanup failures.
            val operationErrors = error.suppressed.filterIsInstance<AnalysisException>().map { it.error }
            cleanupErrors += operationErrors
            operationStopReasons += operationErrors.mapNotNull { partialReason(it.code) }
        } catch (_: Exception) {
            // Unexpected parser/driver/implementation exceptions may contain SQL or credentials.
            failure = AnalysisError(ErrorCode.INTERNAL_ERROR, "Analysis component failed unexpectedly.")
        } finally {
            val session = activeSession.getAndSet(null)
            if (session != null) {
                if (context.isCancelled || context.remainingTime().isZero) {
                    try { session.cancel() } catch (_: Exception) { cleanupErrors += cleanupError() }
                }
                try { session.close() } catch (_: Exception) { cleanupErrors += cleanupError() }
            }
            try { registration?.close() } catch (_: Exception) { cleanupErrors += cleanupError() }
        }

        val finalResult = finalizeResult(
            objective = input.objective,
            baseline = baseline,
            checkpoint = checkpoint,
            result = result,
            failure = failure,
            cleanupErrors = cleanupErrors,
            operationStopReasons = operationStopReasons,
            catalogWarnings = catalogWarnings,
            candidateBatch = candidateBatch,
            context = context,
        )

        // Neither report port can access the closed session. This path also accepts an expired run.
        context.enterReport()
        try {
            val builtReport = reportBuilder.build(input, candidateBatch, finalResult, context)
            val report = if (manual == null) builtReport else builtReport.copy(manualComparison = manual)
            require(report.runId == context.runId && report.inputHash == context.inputHash &&
                report.analysisMode == context.analysisMode &&
                report.candidateRanking == input.candidateRanking &&
                report.result == finalResult && report.input == input) {
                "Report must describe this invocation and saved result"
            }
            reportExporter.export(report, outputDirectory, context)
            context.completePhase()
        } catch (error: AnalysisException) {
            if (error.error.code == ErrorCode.EXPORT_FAILED) throw error
            throw exportError()
        } catch (_: Exception) {
            throw exportError()
        }
        return finalResult
    }

    private fun finalizeResult(
        objective: Objective,
        baseline: EvaluationResult?,
        checkpoint: SearchCheckpoint?,
        result: SearchResult?,
        failure: AnalysisError?,
        cleanupErrors: List<AnalysisError>,
        operationStopReasons: List<StopReason>,
        catalogWarnings: List<AnalysisWarning>,
        candidateBatch: CandidateBatch,
        context: RunContext,
    ): SearchResult {
        val reportedFailure = failure ?: (result as? SearchResult.Failed)?.error ?: cleanupErrors.firstOrNull()
        val primaryFailure = if (context.isCancelled && reportedFailure != null &&
            reportedFailure.code != ErrorCode.CANCELLED && partialReason(reportedFailure.code) != null) {
            AnalysisError(ErrorCode.CANCELLED, "Analysis was cancelled.")
        } else reportedFailure
        val savedDiagnostics = checkpoint?.diagnostics ?: SearchDiagnostics()
        val diagnostics = (result?.diagnostics ?: savedDiagnostics).let { prior ->
            prior.copy(
                completedPhases = context.completedPhases(), statistics = context.statistics(), limits = context.limits,
                stopReasons = (savedDiagnostics.stopReasons + prior.stopReasons).distinct(),
                warnings = (savedDiagnostics.warnings + prior.warnings + catalogWarnings +
                    candidateBatch.diagnostics.warnings).distinct(),
                candidateDecisions = (savedDiagnostics.candidateDecisions + prior.candidateDecisions +
                    candidateBatch.diagnostics.decisions).distinct(),
                errors = (savedDiagnostics.errors + prior.errors +
                    listOfNotNull(primaryFailure, reportedFailure) + cleanupErrors).distinct(),
                unexaminedConfigurations = prior.unexaminedConfigurations ?: savedDiagnostics.unexaminedConfigurations,
            )
        }
        return if (primaryFailure != null) {
            val reason = partialReason(primaryFailure.code) ?: if (
                primaryFailure.code == ErrorCode.HYPOTHETICAL_INDEX_ERROR
            ) cleanupErrors.firstNotNullOfOrNull { partialReason(it.code) } else null
            // A recoverable primary failure must not hide a fatal error found during cleanup.
            // Keep the original error as primary, but do not publish a usable result after a
            // numeric, input, catalog or internal failure in any part of that operation.
            val fatalCleanup = cleanupErrors.any { partialReason(it.code) == null }
            if (reason != null && !fatalCleanup && baseline != null && checkpoint != null) {
                SearchResult.Partial(objective, baseline, checkpoint.bestRobust, checkpoint.bestMean,
                    diagnostics.copy(stopReasons = (diagnostics.stopReasons + reason + operationStopReasons).distinct()))
            } else {
                SearchResult.Failed(objective, primaryFailure, baseline, diagnostics)
            }
        } else when (val completed = checkNotNull(result)) {
            is SearchResult.Completed -> completed.copy(diagnostics = diagnostics)
            is SearchResult.Partial -> completed.copy(diagnostics = diagnostics)
            is SearchResult.Failed -> completed.copy(diagnostics = diagnostics)
        }
    }

    private fun validateCheckpoint(saved: SearchCheckpoint, baseline: EvaluationResult) {
        requireCoverage(saved.bestRobust.evaluation, baseline.costByQueryId.keys)
        requireCoverage(saved.bestMean.evaluation, baseline.costByQueryId.keys)
    }

    private fun requireCoverage(evaluation: EvaluationResult, queryIds: Set<String>) {
        if (evaluation.costByQueryId.keys != queryIds) throw AnalysisException(AnalysisError(
            ErrorCode.EXPLAIN_FAILED, "Evaluation did not cover every workload query.",
        ))
    }

    private inline fun <T> checked(context: RunContext, operation: () -> T): T {
        context.checkDeadline()
        val value = operation()
        context.checkDeadline()
        return value
    }

    private fun partialReason(code: ErrorCode): StopReason? = when (code) {
        ErrorCode.DEADLINE_EXCEEDED -> StopReason.DEADLINE_EXCEEDED
        ErrorCode.CANCELLED -> StopReason.CANCELLED
        ErrorCode.EXPLAIN_LIMIT -> StopReason.EXPLAIN_LIMIT
        ErrorCode.REPAIR_LIMIT -> StopReason.REPAIR_LIMIT
        ErrorCode.DB_CONNECTION_ERROR -> StopReason.CONNECTION_LOST
        ErrorCode.EXPLAIN_FAILED -> StopReason.EVALUATION_FAILED
        else -> null
    }

    private fun cleanupError() = AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Analysis session cleanup failed.")
    private fun exportError() = AnalysisException(AnalysisError(ErrorCode.EXPORT_FAILED, "Cannot publish analysis artifacts."))
}
