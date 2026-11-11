package ru.itmo.indexbudget.cli

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.application.JvmShutdownCancellation
import ru.itmo.indexbudget.application.ShutdownCancellation
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.statistics.*
import tools.jackson.databind.json.JsonMapper
import java.io.PrintWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Separate command family; snapshot waits never hold JDBC connections or consume a search deadline. */
@Component
class StatisticsCommand(
    private val collector: StatisticsCollector = JdbcStatisticsCollector(),
    private val shutdown: ShutdownCancellation = JvmShutdownCancellation(),
    private val waitMillis: (Long) -> Unit = Thread::sleep,
) {
    fun execute(args: Array<String>, out: PrintWriter, err: PrintWriter): Int {
        if (args.contentEquals(arrayOf("stats", "--help"))) { out.println(HELP); return 0 }
        var output: Path? = null
        var intervalSeconds: Long? = null
        val completed = mutableListOf<StatisticsSnapshot>()
        try {
            val request = parse(args)
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
                    val snapshot = try { collector.collect(request.sourceId, request.limits, context) }
                    finally { active.set(null) }
                    publish(destination.resolve("snapshot-${index + 1}.json"), SnapshotJson().render(snapshot))
                    completed += snapshot
                    out.println("snapshot-${index + 1}.json: ${if (snapshot.complete) "completed" else "partial"}")
                }
            }
            val status = if (completed.all { it.complete }) "completed" else "partial"
            summary(destination, completed, status, null, request.intervalSeconds)
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
                try { summary(directory, completed, if (completed.isEmpty()) "failed" else "partial", safe.code.name, intervalSeconds) }
                catch (_: Exception) { /* Published snapshots remain usable even if the manifest cannot be written. */ }
            }
            err.println("${safe.code.name}: ${safe.message}")
            return if (completed.isNotEmpty()) 3 else safe.code.defaultExitCode
        }
    }

    private data class Request(val sourceId: UUID, val output: Path, val intervalSeconds: Long?, val limits: SnapshotLimits)

    private fun parse(args: Array<String>): Request {
        val mode = args.getOrNull(1)
        if (mode !in setOf("snapshot", "pair")) invalid("Expected stats snapshot or stats pair; see stats --help.")
        val values = linkedMapOf<String, String>()
        var i = 2
        while (i < args.size) {
            val key = args[i]
            val value = args.getOrNull(i + 1)
            if (key !in OPTIONS || key in values || value == null || value.startsWith("--")) invalid("Invalid statistics options; see stats --help.")
            values[key] = value
            i += 2
        }
        try {
            val sourceId = UUID.fromString(values.getValue("--source-id"))
            require(sourceId.toString() == values.getValue("--source-id").lowercase())
            val path = Path.of(values.getValue("--output-dir"))
            require(path.toString().isNotBlank())
            val interval = values["--interval-seconds"]?.toLong()
            require(if (mode == "pair") interval != null && interval in 1..86_400 else interval == null)
            val limits = SnapshotLimits(
                maxRows = values["--max-rows"]?.toInt() ?: 10_000,
                maxBytes = values["--max-bytes"]?.toInt() ?: 8 * 1024 * 1024,
                maxQueryBytes = values["--max-query-bytes"]?.toInt() ?: 64 * 1024,
                timeout = Duration.ofSeconds(values["--timeout-seconds"]?.toLong() ?: 30),
            )
            return Request(sourceId, path, interval, limits)
        } catch (_: Exception) { invalid("Invalid statistics options or resource limits; see stats --help.") }
    }

    private fun summary(path: Path, snapshots: List<StatisticsSnapshot>, status: String, error: String?, interval: Long?) {
        val comparable = if (snapshots.size == 2) {
            val first = snapshots[0]; val second = snapshots[1]
            first.complete && second.complete && first.source == second.source &&
                first.after == second.before && first.before == second.after
        } else null
        val bytes = JsonMapper.builder().build().writeValueAsBytes(linkedMapOf(
            "schema_version" to 1, "kind" to "pg_stat_statements_capture", "status" to status,
            "error" to error, "interval_seconds" to interval, "conditions_match" to comparable,
            "snapshots" to snapshots.mapIndexed { index, snapshot -> mapOf(
                "path" to "snapshot-${index + 1}.json", "snapshot_id" to snapshot.snapshotId.toString(),
                "status" to if (snapshot.complete) "completed" else "partial") },
        ))
        publish(path.resolve("capture.json"), bytes)
    }

    private fun publish(path: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(path.parent, ".snapshot-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, path, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun invalid(message: String): Nothing = throw AnalysisException(AnalysisError(ErrorCode.INVALID_INPUT, message))

    companion object {
        private val OPTIONS = setOf("--source-id", "--output-dir", "--interval-seconds", "--max-rows", "--max-bytes", "--max-query-bytes", "--timeout-seconds")
        val HELP = """
            indexbudget stats snapshot --source-id <stable UUID> --output-dir <new directory>
            indexbudget stats pair --source-id <stable UUID> --output-dir <new directory> --interval-seconds <1..86400>
            Optional limits per snapshot:
              --max-rows <1..100000>             default 10000
              --max-bytes <32768..67108864>       default 8388608, including metadata
              --max-query-bytes <1..1048576>      default 65536; oversized text is omitted, not truncated
              --timeout-seconds <1..300>         default 30, independent of index search
            Reads pg_stat_statements for the current PostgreSQL 16 database, before any ranking.
            Connection: INDEXBUDGET_DATABASE_URL. Source UUID is operator-assigned, never a DSN.
            Each collection closes its connection before the observation interval.
            Exit: 0 complete; 3 partial; 2 invalid input; 1 failed. No reset, setup or DDL is executed.
        """.trimIndent()
    }
}
