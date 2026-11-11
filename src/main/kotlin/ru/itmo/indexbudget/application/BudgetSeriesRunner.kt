package ru.itmo.indexbudget.application

import ru.itmo.indexbudget.domain.*
import java.nio.file.Path

fun interface BudgetSeriesRunner {
    fun run(input: WorkloadInput, request: BudgetSeriesRequest, outputDirectory: Path, context: RunContext): BudgetSeriesResult
}

data class SeriesPreparation(val pool: PreparedCandidatePool, val conditions: SeriesConditions)
data class SeriesPointRun(val result: SearchResult, val conditions: SeriesConditions?, val report: AnalysisReport? = null)

/** Implemented by the ordinary pipeline; each call owns and closes a separate session. */
interface SeriesAnalysisRunner {
    fun prepareSeriesPool(input: WorkloadInput, context: RunContext): SeriesPreparation
    fun runSeriesPoint(input: WorkloadInput, outputDirectory: Path, context: RunContext, pool: PreparedCandidatePool): SeriesPointRun
}
