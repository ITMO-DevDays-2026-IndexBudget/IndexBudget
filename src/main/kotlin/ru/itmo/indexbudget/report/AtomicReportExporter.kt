package ru.itmo.indexbudget.report

import ru.itmo.indexbudget.application.OutputDirectoryValidator
import ru.itmo.indexbudget.candidates.DdlRenderer
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.AnalysisReport
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.RunStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/**
 * Stages the entire run beside its destination, then publishes it with one directory rename.
 * No per-file fallback: a filesystem without atomic directory moves reports EXPORT_FAILED.
 * A nonempty destination cannot be replaced by a directory rename, including a competing run.
 */
class AtomicReportExporter(
    private val renderer: ReportRenderer,
    private val jsonWriter: JsonReportWriter = JsonReportWriter(),
    private val ddlRenderer: DdlRenderer = PostgresDdlRenderer(),
) : ReportExporter {
    override fun export(report: AnalysisReport, outputDirectory: Path, context: RunContext) {
        var staging: Path? = null
        try {
            validateIdentity(report, context)
            val destination = resolveDestination(outputDirectory)
            val temporary = Files.createTempDirectory(destination.parent, ".indexbudget-${report.runId}-")
            staging = temporary

            write(temporary.resolve("report.json"), jsonWriter.render(report))
            write(temporary.resolve("report.html"), renderer.render(report))
            if (report.result.status != RunStatus.FAILED) {
                write(temporary.resolve("indexes.sql"), renderSql(report))
            }

            // Report finalization is also allowed after timeout/cancellation. All evaluations and
            // plans already exist; there is no SQL or new analysis to authorize with checkDeadline.
            OutputDirectoryValidator().validate(destination)
            Files.move(temporary, destination, ATOMIC_MOVE)
            staging = null
        } catch (_: Exception) {
            // Renderer/IO exception messages can contain paths, SQL or connection credentials.
            // Only the stable public error crosses the CLI boundary.
            throw AnalysisException(AnalysisError(ErrorCode.EXPORT_FAILED, "Cannot publish analysis artifacts."))
        } finally {
            staging?.let(::discardStaging)
        }
    }

    private fun validateIdentity(report: AnalysisReport, context: RunContext) {
        require(report.runId == context.runId && report.inputHash == context.inputHash)
        require(report.runId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(report.schemaVersion == 2 && report.specVersion == "0.2")
        val hash = report.inputHash
        require(hash == null || hash.matches(Regex("[a-f0-9]{64}")))
        require(report.result.status == RunStatus.FAILED || hash != null)
        require(report.comparisonScope == report.result.comparisonScope)
        require(report.input == null || report.input.objective == report.result.selectedObjective)
        val candidatesById = report.candidates.candidates.groupBy { it.id }
        for (configuration in listOfNotNull(report.result.selected, report.result.bestRobust, report.result.bestMean)) {
            require(configuration.evaluation.candidateIds.all { candidatesById[it]?.size == 1 })
        }
    }

    private fun resolveDestination(outputDirectory: Path): Path {
        val output = outputDirectory.toAbsolutePath()
        OutputDirectoryValidator().validate(output)
        Files.createDirectories(output.parent)
        // Preserve a final symlink: publication replaces the empty target directory, not the link.
        // Resolving existing parents also keeps '..' after a symlink consistent with filesystem IO.
        return if (Files.exists(output)) output.toRealPath()
        else output.parent.toRealPath().resolve(output.fileName)
    }

    private fun renderSql(report: AnalysisReport): String = buildString {
        append("-- IndexBudget recommendations; not executed automatically.\n")
        append("-- run_id: ").append(report.runId).append('\n')
        append("-- input_hash: ").append(report.inputHash).append('\n')
        append("-- selected_objective: ").append(report.result.selectedObjective.name.lowercase()).append("\n\n")
        val candidatesById = report.candidates.candidates.associateBy { it.id }
        val selected = requireNotNull(report.result.selected)
        for (id in selected.evaluation.candidateIds) {
            val candidate = checkNotNull(candidatesById[id])
            val ddl = ddlRenderer.render(candidate.definition)
            require(candidate.ddl == null || candidate.ddl == ddl) { "Selected DDL must match its definition" }
            append(ddl).append('\n')
        }
    }

    private fun write(path: Path, content: String) {
        Files.newBufferedWriter(path, Charsets.UTF_8, CREATE_NEW, WRITE).use { it.write(content) }
    }

    private fun discardStaging(directory: Path) {
        // Only these names can belong to this exporter. Never recursively clean a destination or
        // another run, even if cleanup encounters a concurrent filesystem change.
        for (name in listOf("report.json", "report.html", "indexes.sql")) {
            try { Files.deleteIfExists(directory.resolve(name)) } catch (_: Exception) { /* best effort */ }
        }
        try { Files.deleteIfExists(directory) } catch (_: Exception) { /* best effort */ }
    }
}
