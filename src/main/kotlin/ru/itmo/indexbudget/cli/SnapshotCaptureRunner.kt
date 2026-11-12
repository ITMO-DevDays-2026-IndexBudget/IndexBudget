package ru.itmo.indexbudget.cli

import ru.itmo.indexbudget.application.ShutdownCancellation
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.statistics.SnapshotLimits
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class CaptureRequest(val sourceId: UUID, val output: Path, val intervalSeconds: Long?, val limits: SnapshotLimits)

/** Shared ownership of cancellation, independent read contexts, waiting and incremental publication. */
internal class SnapshotCaptureRunner(
    private val shutdown: ShutdownCancellation,
    private val waitMillis: (Long) -> Unit,
) {
    fun <T> execute(
        requestFactory: () -> CaptureRequest,
        collect: (CaptureRequest, RunContext) -> T,
        render: (T) -> ByteArray,
        isComplete: (T) -> Boolean,
        completionStatus: (List<T>) -> String,
        manifest: (List<T>, String, String?, Long?) -> ByteArray,
        out: PrintWriter,
        err: PrintWriter,
    ): Int {
        var output: Path? = null
        var intervalSeconds: Long? = null
        val completed = mutableListOf<T>()
        try {
            val request = requestFactory()
            intervalSeconds = request.intervalSeconds
            val destination = request.output.toAbsolutePath()
            if (Files.exists(destination)) invalid("Statistics output directory must be new.")
            Files.createDirectories(destination.parent)
            Files.createDirectory(destination)
            output = destination
            val cancelled = AtomicBoolean()
            val active = AtomicReference<RunContext?>()
            shutdown.register { cancelled.set(true); active.get()?.cancel() }.use {
                repeat(if (request.intervalSeconds == null) 1 else 2) { index ->
                    if (index > 0) {
                        // Use monotonic elapsed time, not wall time. The previous connection is already closed.
                        val until = System.nanoTime() + Duration.ofSeconds(request.intervalSeconds!!).toNanos()
                        while (System.nanoTime() < until) {
                            if (cancelled.get()) throw AnalysisException(AnalysisError(ErrorCode.CANCELLED, "Observation cancelled."))
                            waitMillis(minOf(100, ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(1)))
                        }
                    }
                    if (cancelled.get()) throw AnalysisException(AnalysisError(ErrorCode.CANCELLED, "Observation cancelled."))
                    val context = RunContext(limits = RunLimits(commandTimeout = request.limits.timeout))
                    active.set(context)
                    if (cancelled.get()) context.cancel()
                    val snapshot = try { collect(request, context) }
                    finally { active.set(null) }
                    publish(destination.resolve("snapshot-${index + 1}.json"), render(snapshot))
                    completed += snapshot
                    out.println("snapshot-${index + 1}.json: ${if (isComplete(snapshot)) "completed" else "partial"}")
                }
            }
            val status = completionStatus(completed)
            publish(destination.resolve("capture.json"), manifest(completed, status, null, request.intervalSeconds))
            return if (status == "completed") 0 else 3
        } catch (error: Exception) {
            val safe = when (error) {
                is AnalysisException -> error.error
                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    AnalysisError(ErrorCode.CANCELLED, "Observation interrupted.")
                }
                is IOException, is SecurityException -> AnalysisError(ErrorCode.EXPORT_FAILED, "Cannot publish statistics snapshots.")
                else -> AnalysisError(ErrorCode.STATISTICS_READ_FAILED, "Cannot collect or publish statistics snapshots.")
            }
            // Never remove the first published snapshot when a second collection/wait fails.
            output?.let { directory ->
                try { publish(directory.resolve("capture.json"), manifest(completed, if (completed.isEmpty()) "failed" else "partial", safe.code.name, intervalSeconds)) }
                catch (_: Exception) { /* Published snapshots remain usable even if the manifest cannot be written. */ }
            }
            err.println("${safe.code.name}: ${safe.message}")
            return if (completed.isNotEmpty()) 3 else safe.code.defaultExitCode
        }
    }

    private fun publish(path: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(path.parent, ".snapshot-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, path, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun invalid(message: String): Nothing = throw AnalysisException(AnalysisError(ErrorCode.INVALID_INPUT, message))
}
