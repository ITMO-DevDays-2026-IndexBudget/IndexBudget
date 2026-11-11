package ru.itmo.indexbudget.application

import ru.itmo.indexbudget.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Common preparation followed by independent ordinary analyses, publishing after every point. */
class BudgetSeriesPipeline(
    private val analysis: SeriesAnalysisRunner,
    private val shutdownCancellation: ShutdownCancellation = JvmShutdownCancellation(),
) : BudgetSeriesRunner {
    override fun run(input: WorkloadInput, request: BudgetSeriesRequest, outputDirectory: Path,
        context: RunContext): BudgetSeriesResult {
        request.validate(input)
        context.selectSeriesTimeout(Duration.ofSeconds(request.timeoutSeconds))
        OutputDirectoryValidator().validate(outputDirectory)
        try { Files.createDirectories(outputDirectory) } catch (_: Exception) { throw exportError() }
        val points = request.values.mapIndexed { index, value ->
            BudgetSeriesPoint(index + 1, value, SeriesPointStatus.SKIPPED)
        }.toMutableList()
        var preparation: SeriesPreparation? = null
        var preparationContext: RunContext? = null
        var preparationError: AnalysisError? = null
        val writer = BudgetSeriesWriter(outputDirectory, input, request, context)
        val registration = shutdownCancellation.register(context::cancel)
        try {
            writer.publish(BudgetSeriesResult(SeriesStatus.PARTIAL, points.toList()), null, null, finished = false)
            try {
                val maximumInput = request.inputAt(input, checkNotNull(request.values.maxOrNull()))
                preparationContext = child(context, input.analysisMode)
                preparation = analysis.prepareSeriesPool(maximumInput, preparationContext)
                context.checkDeadline()
            } catch (error: Exception) {
                preparationError = safeError(error)
            }
            val prepared = preparation
            if (prepared != null && preparationError == null) {
                for ((index, value) in request.values.withIndex()) {
                    val stop = controlError(context)
                    if (stop != null) {
                        for (remaining in index until points.size) points[remaining] = points[remaining].copy(error = stop)
                        break
                    }
                    val runContext = child(context, input.analysisMode)
                    val runDirectory = "point-%02d".format(index + 1)
                    val pointPath = outputDirectory.resolve(runDirectory)
                    points[index] = try {
                        val run = analysis.runSeriesPoint(request.inputAt(input, value), pointPath, runContext, prepared.pool)
                        val changes = changes(prepared.conditions, run.conditions)
                        val artifacts = listOf("report.json", "report.html", "indexes.sql")
                            .filter { Files.isRegularFile(pointPath.resolve(it)) }
                            .associateWith { "$runDirectory/$it" }
                        BudgetSeriesPoint(index + 1, value, SeriesPointStatus.valueOf(run.result.status.name),
                            runContext.runId, run.result, run.conditions, run.conditions?.let { changes.isEmpty() },
                            changes, artifacts, (run.result as? SearchResult.Failed)?.error)
                    } catch (error: Exception) {
                        BudgetSeriesPoint(index + 1, value, SeriesPointStatus.FAILED,
                            runId = runContext.runId, error = safeError(error))
                    }
                    writer.publish(result(points), prepared, preparationContext, finished = false)
                }
            } else {
                for (index in points.indices) points[index] = points[index].copy(error = preparationError)
            }
            val result = result(points, preparationError)
            writer.publish(result, preparation, preparationContext, finished = true)
            return result
        } finally {
            registration.close()
        }
    }

    private fun child(parent: RunContext, mode: AnalysisMode): RunContext = parent.child(mode).also { child ->
        parent.inputHash?.let(child::recordInputHash)
    }

    private fun result(points: List<BudgetSeriesPoint>, preparationError: AnalysisError? = null): BudgetSeriesResult {
        val usable = points.any { it.result?.selected != null }
        val complete = points.all { it.status == SeriesPointStatus.COMPLETED && it.conditionsMatch == true }
        return BudgetSeriesResult(when {
            preparationError != null || !usable -> SeriesStatus.FAILED
            complete -> SeriesStatus.COMPLETED
            else -> SeriesStatus.PARTIAL
        }, points.toList(), preparationError)
    }

    private fun changes(reference: SeriesConditions, observed: SeriesConditions?): List<String> = buildList {
        if (observed == null) { add("conditions_unavailable"); return@buildList }
        if (reference.catalogHash != observed.catalogHash) add("catalog")
        if (reference.baselineCosts != observed.baselineCosts) add("baseline_costs")
        if (reference.baselinePlansHash != observed.baselinePlansHash) add("baseline_plans")
        if (reference.databaseVersions != observed.databaseVersions) add("database_versions")
        if (reference.sourceFingerprint != observed.sourceFingerprint) add("source")
    }

    private fun controlError(context: RunContext): AnalysisError? = try {
        context.checkDeadline(); null
    } catch (error: AnalysisException) { error.error }

    private fun safeError(error: Exception): AnalysisError = (error as? AnalysisException)?.error ?:
        AnalysisError(ErrorCode.INTERNAL_ERROR, "Budget series operation failed unexpectedly.")

    private fun exportError() = AnalysisException(AnalysisError(ErrorCode.EXPORT_FAILED, "Cannot publish budget series artifacts."))
}
