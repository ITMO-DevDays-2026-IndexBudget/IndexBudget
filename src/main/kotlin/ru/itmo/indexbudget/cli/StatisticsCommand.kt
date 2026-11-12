package ru.itmo.indexbudget.cli

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.application.JvmShutdownCancellation
import ru.itmo.indexbudget.application.ShutdownCancellation
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.statistics.*
import tools.jackson.databind.json.JsonMapper
import java.io.PrintWriter
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** Separate command family; snapshot waits never hold JDBC connections or consume a search deadline. */
@Component
class StatisticsCommand(
    private val collector: StatisticsCollector = JdbcStatisticsCollector(),
    private val shutdown: ShutdownCancellation = JvmShutdownCancellation(),
    private val waitMillis: (Long) -> Unit = Thread::sleep,
) {
    fun execute(args: Array<String>, out: PrintWriter, err: PrintWriter): Int {
        if (args.contentEquals(arrayOf("stats", "--help"))) { out.println(HELP); return 0 }
        return SnapshotCaptureRunner(shutdown, waitMillis).execute<StatisticsSnapshot>(
            requestFactory = { parse(args) },
            collect = { request, context -> collector.collect(request.sourceId, request.limits, context) },
            render = { SnapshotJson().render(it) }, isComplete = { it.complete },
            completionStatus = { snapshots -> if (snapshots.all { it.complete }) "completed" else "partial" },
            manifest = ::manifest, out = out, err = err,
        )
    }

    private fun parse(args: Array<String>): CaptureRequest {
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
            return CaptureRequest(sourceId, path, interval, limits)
        } catch (_: Exception) { invalid("Invalid statistics options or resource limits; see stats --help.") }
    }

    private fun manifest(snapshots: List<StatisticsSnapshot>, status: String, error: String?, interval: Long?): ByteArray {
        val comparable = if (snapshots.size == 2) {
            val first = snapshots[0]; val second = snapshots[1]
            first.complete && second.complete && first.source == second.source &&
                first.after == second.before && first.before == second.after
        } else null
        return JsonMapper.builder().build().writeValueAsBytes(linkedMapOf(
            "schema_version" to 1, "kind" to "pg_stat_statements_capture", "status" to status,
            "error" to error, "interval_seconds" to interval, "conditions_match" to comparable,
            "snapshots" to snapshots.mapIndexed { index, snapshot -> mapOf(
                "path" to "snapshot-${index + 1}.json", "snapshot_id" to snapshot.snapshotId.toString(),
                "status" to if (snapshot.complete) "completed" else "partial") },
        ))
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
