package ru.itmo.indexbudget.statistics

import ru.itmo.indexbudget.domain.*
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** Strict, bounded readers for saved collector output and importer configuration. */
internal object ImportJson {
    val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    fun read(path: Path, maxBytes: Int, context: RunContext): JsonNode {
        context.checkDeadline()
        val bytes = Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }
        if (bytes.size > maxBytes) fail("Import file exceeds its byte limit.", ErrorCode.INPUT_LIMIT_EXCEEDED)
        val tree = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            mapper.readTree(text)
        } catch (_: Exception) { fail("Import file must contain valid UTF-8 JSON without duplicate keys or trailing content.") }
        context.checkDeadline()
        if (tree == null || !tree.isObject) fail("Import file must contain an object.")
        return tree
    }

    fun text(node: JsonNode?, name: String): String = node?.get(name)?.takeIf { it.isString }?.asString()
        ?.takeIf { it.isNotBlank() } ?: fail("Missing or invalid string field: $name.")
    fun long(node: JsonNode?, name: String): Long = node?.get(name)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
        ?.longValue() ?: fail("Missing or invalid integer field: $name.")
    fun decimalLong(node: JsonNode, name: String): Long = text(node, name).toLongOrNull()
        ?: fail("Invalid decimal bigint field: $name.")
    fun number(node: JsonNode, name: String): Double {
        val decimal = node.get(name)?.takeIf { it.isNumber }?.decimalValue() ?: fail("Invalid numeric field: $name.")
        val value = decimal.toDouble()
        if (!value.isFinite() || decimal.signum() < 0 || (decimal.signum() > 0 && value == 0.0))
            fail("Invalid numeric range: $name.", ErrorCode.NUMERIC_ERROR)
        return value
    }
    fun bool(node: JsonNode, name: String): Boolean = node.get(name)?.takeIf { it.isBoolean }?.booleanValue()
        ?: fail("Invalid boolean field: $name.")
    fun array(node: JsonNode, name: String): List<JsonNode> = node.get(name)?.takeIf { it.isArray }?.toList()
        ?: fail("Missing array field: $name.")
    fun fields(node: JsonNode, allowed: Set<String>) {
        if (!node.isObject || node.properties().any { it.key !in allowed }) fail("Unknown fields in import input.")
    }
    fun key(node: JsonNode) = StatisticsKey(UUID.fromString(text(node, "source_id")), long(node, "dbid"),
        long(node, "userid"), decimalLong(node, "queryid"), bool(node, "toplevel"))
    fun key(value: StatisticsKey): Map<String, Any> = linkedMapOf("source_id" to value.sourceId.toString(),
        "dbid" to value.dbid, "userid" to value.userid, "queryid" to value.queryid.toString(), "toplevel" to value.toplevel)

    fun snapshot(path: Path, context: RunContext): StatisticsSnapshot {
        val root = read(path, 64 * 1024 * 1024, context)
        fields(root, setOf("schema_version", "kind", "snapshot_id", "source_id", "scope", "postgres_version", "extension_version",
            "started_at", "finished_at", "before", "after", "status", "reasons", "row_count", "unavailable_text_count", "limits", "entries"))
        if (long(root, "schema_version") != 1L || text(root, "kind") != "pg_stat_statements_snapshot")
            fail("Expected pg_stat_statements_snapshot v1.")
        val scope = root.get("scope") ?: fail("Snapshot scope is required.")
        fields(scope, setOf("database_oid", "reader_userid", "rows", "all_query_texts_visible", "ranking", "track"))
        if (text(scope, "rows") != "current_database_all_users" || text(scope, "ranking") != "none")
            fail("Snapshot must cover current database before ranking.")
        val limits = root.get("limits") ?: fail("Snapshot limits are required.")
        fields(limits, setOf("max_rows", "max_bytes", "max_query_bytes", "timeout_millis"))
        val maxRows = long(limits, "max_rows")
        val maxBytes = long(limits, "max_bytes")
        val maxQuery = long(limits, "max_query_bytes")
        if (maxRows !in 1..100_000 || maxBytes !in 32_768..67_108_864 || maxQuery !in 1..1_048_576)
            fail("Invalid snapshot resource limits.")
        if (Files.size(path) > maxBytes) fail("Snapshot exceeds declared byte limit.")
        val entries = array(root, "entries")
        if (entries.size > maxRows) fail("Snapshot exceeds declared row limit.")
        fun epoch(node: JsonNode) = StatisticsEpoch(text(node, "stats_reset"), long(node, "dealloc"))
        val snapshot = StatisticsSnapshot(UUID.fromString(text(root, "snapshot_id")), StatisticsSource(
            UUID.fromString(text(root, "source_id")), long(scope, "database_oid"), long(scope, "reader_userid"),
            bool(scope, "all_query_texts_visible"), text(root, "postgres_version"), text(root, "extension_version"), text(scope, "track")),
            text(root, "started_at"), text(root, "finished_at"), epoch(root.get("before")),
            root.get("after")?.takeUnless { it.isNull }?.let(::epoch), entries.map { row ->
                context.checkDeadline()
                fields(row, setOf("dbid", "userid", "queryid", "toplevel", "query", "text_status", "calls", "total_exec_time", "rows"))
                StatisticsEntry(long(row, "dbid"), long(row, "userid"),
                    row.get("queryid")?.takeUnless { it.isNull }?.let { decimalLong(row, "queryid") },
                    bool(row, "toplevel"), row.get("query")?.takeUnless { it.isNull }?.let { text(row, "query") },
                    QueryTextStatus.valueOf(text(row, "text_status").uppercase()), long(row, "calls"),
                    number(row, "total_exec_time"), long(row, "rows"))
            }, array(root, "reasons").map { SnapshotReason.valueOf(it.asString()) },
            SnapshotLimits(maxRows.toInt(), maxBytes.toInt(), maxQuery.toInt(), Duration.ofMillis(long(limits, "timeout_millis"))))
        if (long(root, "row_count") != entries.size.toLong() ||
            text(root, "status") != if (snapshot.complete) "completed" else "partial") fail("Inconsistent snapshot status or row count.")
        return snapshot
    }

    fun fail(message: String, code: ErrorCode = ErrorCode.INVALID_INPUT): Nothing = throw AnalysisException(AnalysisError(code, message))
}
