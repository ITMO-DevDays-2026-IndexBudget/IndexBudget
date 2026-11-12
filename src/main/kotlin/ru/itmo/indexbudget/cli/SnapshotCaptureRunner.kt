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
            val destination = prepareDestination(request.output)
            output = destination
            captureSnapshots(request, destination, collect, render, isComplete, completed, out)
            val status = completionStatus(completed)
            publish(destination.resolve("capture.json"), manifest(completed, status, null, request.intervalSeconds))
            return if (status == "completed") 0 else 3
        } catch (error: Exception) {
            val safe = safeError(error)
            // Never remove the first published snapshot when a second collection/wait fails.
            output?.let { directory ->
                try {
                    publish(directory.resolve("capture.json"), manifest(
                        completed, if (completed.isEmpty()) "failed" else "partial", safe.code.name, intervalSeconds,
                    ))
                } catch (_: Exception) {
                    // Published snapshots remain usable even if the manifest cannot be written.
                }
            }
            err.println("${safe.code.name}: ${safe.message}")
            return if (completed.isNotEmpty()) 3 else safe.code.defaultExitCode
        }
    }

    private fun prepareDestination(output: Path): Path {
        val destination = output.toAbsolutePath()
        if (Files.exists(destination)) invalid("Statistics output directory must be new.")
        Files.createDirectories(destination.parent)
        Files.createDirectory(destination)
        return destination
    }

    private fun <T> captureSnapshots(
        request: CaptureRequest,
        destination: Path,
        collect: (CaptureRequest, RunContext) -> T,
        render: (T) -> ByteArray,
        isComplete: (T) -> Boolean,
        completed: MutableList<T>,
        out: PrintWriter,
    ) {
        val control = CaptureControl()
        shutdown.register(control::cancel).use {
            repeat(if (request.intervalSeconds == null) 1 else 2) { index ->
                if (index > 0) waitForNextSnapshot(checkNotNull(request.intervalSeconds) {
                    "Paired capture requires an observation interval"
                }, control)
                val snapshot = control.collect(request, collect)
                val fileName = "snapshot-${index + 1}.json"
                publish(destination.resolve(fileName), render(snapshot))
                completed += snapshot
                out.println("$fileName: ${if (isComplete(snapshot)) "completed" else "partial"}")
            }
        }
    }

    private fun waitForNextSnapshot(intervalSeconds: Long, control: CaptureControl) {
        // Use monotonic elapsed time, not wall time. The previous connection is already closed.
        val until = System.nanoTime() + Duration.ofSeconds(intervalSeconds).toNanos()
        while (System.nanoTime() < until) {
            control.ensureActive()
            waitMillis(minOf(100, ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(1)))
        }
    }

    private class CaptureControl {
        private val cancelled = AtomicBoolean()
        private val active = AtomicReference<RunContext?>()

        fun cancel() {
            cancelled.set(true)
            active.get()?.cancel()
        }

        fun ensureActive() {
            if (cancelled.get()) throw AnalysisException(AnalysisError(ErrorCode.CANCELLED, "Observation cancelled."))
        }

        fun <T> collect(request: CaptureRequest, collect: (CaptureRequest, RunContext) -> T): T {
            ensureActive()
            val context = RunContext(limits = RunLimits(commandTimeout = request.limits.timeout))
            active.set(context)
            if (cancelled.get()) context.cancel()
            return try {
                collect(request, context)
            } finally {
                active.set(null)
            }
        }
    }

    private fun safeError(error: Exception): AnalysisError = when (error) {
        is AnalysisException -> error.error
        is InterruptedException -> {
            Thread.currentThread().interrupt()
            AnalysisError(ErrorCode.CANCELLED, "Observation interrupted.")
        }
        is IOException, is SecurityException -> AnalysisError(ErrorCode.EXPORT_FAILED, "Could not save the statistics snapshots.")
        else -> AnalysisError(ErrorCode.STATISTICS_READ_FAILED, "Could not collect or save the statistics snapshots.")
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
