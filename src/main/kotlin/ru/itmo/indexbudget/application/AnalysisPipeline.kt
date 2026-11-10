package ru.itmo.indexbudget.application

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
) : AnalysisRunner {
    override fun run(input: WorkloadInput, outputDirectory: Path, context: RunContext): SearchResult {
        var baseline: EvaluationResult? = null
        var checkpoint: SearchCheckpoint? = null
        var candidateBatch = CandidateBatch(emptyList())
        var result: SearchResult? = null
        var failure: AnalysisError? = null
        val cleanupErrors = mutableListOf<AnalysisError>()
        val catalogWarnings = mutableListOf<AnalysisWarning>()
        val activeSession = AtomicReference<AnalysisSession?>()
        var registration: AutoCloseable? = null
        try {
            registration = shutdownCancellation.register {
                context.cancel()
                // The adapter owns thread-safe cancellation of a pending statement/connection.
                try { activeSession.get()?.cancel() } catch (_: Exception) { /* close runs in finally */ }
            }
            context.enterPhase(AnalysisPhase.INTROSPECTION)
            val parsed = checked(context) { sqlAnalyzer.parse(input, context) }
            val session = sessionFactory.open(context)
            activeSession.set(session)
            context.checkDeadline()
            val catalog = checked(context) { session.readCatalog(parsed.referencedTables, context) }
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
            context.completePhase()

            context.startSearch()
            context.enterPhase(AnalysisPhase.BASELINE)
            val evaluatedBaseline = session.evaluate(emptyList(), resolved, context)
            requireCoverage(evaluatedBaseline, input.queries.map { it.id }.toSet())
            require(evaluatedBaseline.candidateIds.isEmpty()) { "Baseline must be the empty index set" }
            baseline = evaluatedBaseline
            val budget = metrics.assessBudget(emptyList(), input)
            require(budget.admissible) { "The empty set must be admissible" }
            val empty = EvaluatedConfiguration(
                evaluatedBaseline, metrics.calculate(input, evaluatedBaseline, evaluatedBaseline, budget.usage),
            )
            checkpoint = SearchCheckpoint(empty, empty)
            // Save the full baseline before detecting a late return from an external operation.
            context.checkDeadline()
            context.completePhase()
            val earlyStop = when {
                input.constraints.storageBudgetBytes == 0L -> StopReason.ZERO_STORAGE_BUDGET
                input.constraints.maxIndexes == 0 -> StopReason.ZERO_MAX_INDEXES
                empty.metrics.profiles.all { it.baselineCost == 0.0 } -> StopReason.ZERO_BASELINE
                else -> null
            }
            if (earlyStop != null) {
                result = SearchResult.Completed(input.objective, evaluatedBaseline, empty, empty,
                    SearchDiagnostics(stopReasons = listOf(earlyStop)))
            } else {
                context.enterPhase(AnalysisPhase.CANDIDATES)
                candidateBatch = candidateGenerator.generate(resolved, catalog, context)
                context.checkDeadline()
                val searchCandidates = prepareCandidateSizes(candidateBatch, session, input, context) { preparedBatch ->
                    candidateBatch = preparedBatch
                }
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
        } catch (error: AnalysisException) {
            failure = error.error
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
            catalogWarnings = catalogWarnings,
            candidateBatch = candidateBatch,
            context = context,
        )

        // Neither report port can access the closed session. This path also accepts an expired run.
        context.enterReport()
        try {
            val report = reportBuilder.build(input, candidateBatch, finalResult, context)
            require(report.runId == context.runId && report.inputHash == context.inputHash &&
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

    /** Preserve the full shortlist for reports; return only usable candidates for search. */
    private fun prepareCandidateSizes(
        shortlist: CandidateBatch,
        session: AnalysisSession,
        input: WorkloadInput,
        context: RunContext,
        onPrepared: (CandidateBatch) -> Unit,
    ): CandidateBatch {
        var reportBatch = shortlist
        val preparedCandidates = mutableListOf<IndexCandidate>()
        val reportedCandidates = mutableListOf<IndexCandidate>()
        val shortlistedCandidates = shortlist.candidates
        for ((index, candidate) in shortlistedCandidates.withIndex()) {
            context.checkDeadline()
            try {
                val bytes = session.estimateSize(candidate, context)
                if (bytes < 0) throw AnalysisException(AnalysisError(
                    ErrorCode.NUMERIC_ERROR, "Estimated index size must be nonnegative.",
                ))
                val sized = candidate.copy(estimatedBytes = bytes)
                val usage = metrics.assessBudget(listOf(sized), input).usage
                val prepared = sized.copy(budgetedBytes = usage.budgetedBytes,
                    writeUnitsPerHour = usage.writeUnitsPerHour)
                preparedCandidates += prepared
                reportedCandidates += prepared
            } catch (error: AnalysisException) {
                if (error.error.code != ErrorCode.HYPOTHETICAL_INDEX_ERROR) throw error
                reportedCandidates += candidate.copy(estimatedBytes = null, budgetedBytes = null,
                    writeUnitsPerHour = null)
                reportBatch = reportBatch.copy(diagnostics = reportBatch.diagnostics.copy(
                    decisions = reportBatch.diagnostics.decisions + CandidateDecision(
                        CandidateReason.HYPOTHETICAL_INDEX_ERROR, candidateId = candidate.id,
                        phase = AnalysisPhase.CANDIDATES,
                    ),
                    warnings = reportBatch.diagnostics.warnings + AnalysisWarning(
                        WarningCode.CANDIDATE_REJECTED, "A hypothetical index could not be created.",
                        details = mapOf("candidate_id" to candidate.id),
                    ),
                ))
            }
            // Publish before checking the deadline: failed and unvisited entries keep unknown estimates.
            reportBatch = reportBatch.copy(
                candidates = reportedCandidates.toList() + shortlistedCandidates.drop(index + 1),
            )
            onPrepared(reportBatch)
            context.checkDeadline()
        }
        return reportBatch.copy(candidates = preparedCandidates.toList())
    }

    private fun finalizeResult(
        objective: Objective,
        baseline: EvaluationResult?,
        checkpoint: SearchCheckpoint?,
        result: SearchResult?,
        failure: AnalysisError?,
        cleanupErrors: List<AnalysisError>,
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
            val reason = partialReason(primaryFailure.code)
            if (reason != null && baseline != null && checkpoint != null) {
                SearchResult.Partial(objective, baseline, checkpoint.bestRobust, checkpoint.bestMean,
                    diagnostics.copy(stopReasons = (diagnostics.stopReasons + reason).distinct()))
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
