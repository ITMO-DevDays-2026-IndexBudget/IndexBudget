package ru.itmo.indexbudget.report

import ru.itmo.indexbudget.domain.AnalysisReport
import ru.itmo.indexbudget.domain.CandidateBatch
import ru.itmo.indexbudget.domain.PriceOfRobustness
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.SearchResult
import ru.itmo.indexbudget.domain.WorkloadInput
import java.nio.file.Path

fun interface ReportBuilder {
    /**
     * Uses saved baseline/incumbent costs and plans only; has no database capability.
     * Copy context.inputHash, computed from the validated source JSON before applying DTO defaults.
     * Candidates include rejected shortlist entries with their definitions, provenance and diagnostics;
     * estimates that could not be obtained remain null. This is not the optimizer's filtered batch.
     * Передаёт [PriceOfRobustness.calculate] в AnalysisReport.priceOfRobustness для того же
     * result, comparisonScope=EVALUATED_SETS и selectedObjective; renderer использует готовое поле.
     */
    fun build(input: WorkloadInput, candidates: CandidateBatch, result: SearchResult, context: RunContext): AnalysisReport
}

fun interface ReportExporter {
    /**
     * Publish one run atomically after rechecking the output directory. Never emit successful SQL
     * for FAILED. Use remainingCommandTime; a minimal PARTIAL must still be saved after a search
     * timeout/cancellation. Do not call checkDeadline unconditionally on that fallback path.
     * Throw EXPORT_FAILED on publication failure, including a newly occupied output directory;
     * the CLI must not return success or partial then.
     */
    fun export(report: AnalysisReport, outputDirectory: Path, context: RunContext)
}
