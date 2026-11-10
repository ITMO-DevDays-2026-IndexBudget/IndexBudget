package ru.itmo.indexbudget.application

import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.SearchResult
import ru.itmo.indexbudget.domain.WorkloadInput
import java.nio.file.Path

/** Called after input/output validation, with the context created at command entry. */
fun interface AnalysisRunner {
    fun run(input: WorkloadInput, outputDirectory: Path, context: RunContext): SearchResult
}

/** An explicit integration gap, never a synthetic successful analysis. */
class UnavailableAnalysisRunner(private val missingComponents: List<String> = emptyList()) : AnalysisRunner {
    override fun run(input: WorkloadInput, outputDirectory: Path, context: RunContext): SearchResult {
        context.checkDeadline()
        throw AnalysisException(AnalysisError(
            ErrorCode.NOT_IMPLEMENTED,
            "Analysis components are not connected. No reports were created.",
            details = mapOf("missing_components" to missingComponents.joinToString(",")),
        ))
    }
}
