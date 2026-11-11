package ru.itmo.indexbudget.application

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.AnalysisMode
import ru.itmo.indexbudget.domain.Constraints
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.Objective
import ru.itmo.indexbudget.domain.Profile
import ru.itmo.indexbudget.domain.ProfileWeights
import ru.itmo.indexbudget.domain.QueryInput
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.TableWriteRates
import ru.itmo.indexbudget.domain.WorkloadProvenance
import ru.itmo.indexbudget.domain.ObservationWindow
import ru.itmo.indexbudget.domain.ObservationCoverage
import ru.itmo.indexbudget.domain.QueryExample
import ru.itmo.indexbudget.domain.QueryExampleSource
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import ru.itmo.indexbudget.domain.WorkloadInput
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Проверяет вход до SQL-парсера и БД. Полнота таблиц write profile проверяется после resolution. */
@Component
class WorkloadReader {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        // Сохраняем десятичное значение до проверки: 1e-400 не должно превратиться в допустимый ноль.
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()

    fun read(
        path: Path,
        context: RunContext? = null,
        analysisModeOverride: AnalysisMode? = null,
    ): WorkloadInput {
        // Until the JSON mode is known, allow the largest preset while reading/parsing the source.
        // The clock is never restarted: selecting the actual mode below immediately checks its
        // own command budget, and every remaining validation/analysis operation uses that preset.
        context?.selectAnalysisMode(analysisModeOverride ?: AnalysisMode.DEEP)
        context?.checkDeadline()
        val root = readJsonObject(path, context)
        val version = root.get("schema_version")
        if (version == null || !version.isIntegralNumber || !version.canConvertToInt() || version.intValue() != 2) {
            invalid(Location("schema_version"),
                "Expected schema_version: 2. Migrate v1 frequencies from queries to profiles.calls_per_hour.",
                ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        }
        objectNode(root, Location("workload"), setOf(
            "schema_version", "objective", "queries", "profiles", "constraints", "table_write_rates",
            "analysis_mode", "provenance",
        ))
        val requestedMode = when (val value = root.get("analysis_mode")) {
            null -> AnalysisMode.NORMAL
            else -> when (string(value, Location("analysis_mode"))) {
                "fast" -> AnalysisMode.FAST
                "normal" -> AnalysisMode.NORMAL
                "deep" -> AnalysisMode.DEEP
                else -> invalid(Location("analysis_mode"), "Expected fast, normal or deep.")
            }
        }
        val analysisMode = analysisModeOverride ?: requestedMode
        context?.selectAnalysisMode(analysisMode)
        val objective = when (val value = root.get("objective")) {
            null -> Objective.ROBUST
            else -> when (string(value, Location("objective"))) {
                "robust" -> Objective.ROBUST
                "mean" -> Objective.MEAN
                else -> invalid(Location("objective"), "Expected robust or mean.")
            }
        }
        val constraints = constraints(root.get("constraints"))
        context?.checkDeadline()
        val queries = queries(root.get("queries"))
        context?.checkDeadline()
        val profiles = profiles(root.get("profiles"), queries)
        context?.checkDeadline()
        ProfileWeights.normalize(profiles)
        context?.checkDeadline()
        val writeRates = writeRates(root.get("table_write_rates"))
        if (constraints.writeBudgetUnitsPerHour != null && writeRates == null) {
            invalid(Location("table_write_rates"), "An enabled write budget requires a complete write profile.",
                ErrorCode.INVALID_WRITE_PROFILE)
        }
        val provenance = provenance(root.get("provenance"), queries, profiles)
        context?.checkDeadline()
        if (context != null) {
            val canonical = StringBuilder()
            appendCanonical(root, canonical, context)
            context.checkDeadline()
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8))
            context.checkDeadline()
            context.recordInputHash(HexFormat.of().formatHex(digest))
        }
        return WorkloadInput(queries, profiles, constraints, objective = objective, tableWriteRates = writeRates,
            analysisMode = analysisMode, provenance = provenance)
    }

    /** Sort JSON objects only: array order, string values and absent fields retain their meaning. */
    private fun appendCanonical(node: JsonNode, target: StringBuilder, context: RunContext) {
        context.checkDeadline()
        when {
            node.isObject -> {
                val properties = node.properties().sortedBy { it.key }
                context.checkDeadline()
                target.append('{')
                for ((index, entry) in properties.withIndex()) {
                    context.checkDeadline()
                    if (index > 0) target.append(',')
                    target.append(mapper.writeValueAsString(entry.key)).append(':')
                    appendCanonical(entry.value, target, context)
                }
                target.append('}')
            }
            node.isArray -> {
                target.append('[')
                for ((index, value) in node.withIndex()) {
                    context.checkDeadline()
                    if (index > 0) target.append(',')
                    appendCanonical(value, target, context)
                }
                target.append(']')
            }
            else -> target.append(mapper.writeValueAsString(node))
        }
        context.checkDeadline()
    }

    private fun readJsonObject(path: Path, context: RunContext?): JsonNode {
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_INPUT_BYTES + 1) }
        context?.checkDeadline()
        if (bytes.size > MAX_INPUT_BYTES) {
            invalid(Location("workload"), "Workload JSON must not exceed 1 MiB UTF-8.", ErrorCode.INPUT_LIMIT_EXCEEDED)
        }
        val json = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            invalid(Location("workload"), "Workload JSON must be valid UTF-8.")
        }
        context?.checkDeadline()
        val tree = try {
            mapper.readTree(json)
        } catch (_: JacksonException) {
            // Сообщения парсера могут содержать SQL, пароли или другие фрагменты входа.
            invalid(Location("workload"), "Invalid JSON, duplicate object keys or trailing content.")
        }
        context?.checkDeadline()
        if (tree == null || !tree.isObject) invalid(Location("workload"), "Expected an object.")
        return tree
    }

    private fun constraints(node: JsonNode?): Constraints {
        val location = Location("constraints")
        val value = objectNode(node, location, setOf("storage_budget_bytes", "write_budget_units_per_hour", "max_indexes"))
        val storage = integer(value.get("storage_budget_bytes"), location.child("storage_budget_bytes"))
        if (storage < 0) invalid(location.child("storage_budget_bytes"), "Storage budget must be nonnegative.")
        val writeNode = value.get("write_budget_units_per_hour")
            ?: invalid(location.child("write_budget_units_per_hour"), "Write budget is required; use null to disable it.")
        val write = if (writeNode.isNull) null else number(writeNode, location.child("write_budget_units_per_hour"))
        val maxIndexes = value.get("max_indexes")?.let { integer(it, location.child("max_indexes")) } ?: 5L
        if (maxIndexes !in 0L..5L) invalid(location.child("max_indexes"), "max_indexes must be an integer from 0 to 5.")
        return Constraints(storage, write, maxIndexes.toInt())
    }

    private fun queries(node: JsonNode?): List<QueryInput> {
        val values = array(node, Location("queries"), 20)
        val ids = mutableSetOf<String>()
        return values.mapIndexed { index, entry ->
            val location = Location("queries[$index]")
            val value = objectNode(entry, location, setOf("id", "sql"))
            val id = string(value.get("id"), location.child("id"))
            val queryLocation = location.copy(queryId = id)
            if (!ids.add(id)) invalid(queryLocation.child("id"), "Query IDs must be unique.")
            val sql = string(value.get("sql"), queryLocation.child("sql"))
            if (sql.toByteArray(Charsets.UTF_8).size > MAX_SQL_BYTES) {
                invalid(queryLocation.child("sql"), "SQL must not exceed 64 KiB UTF-8; it was not truncated.",
                    ErrorCode.INPUT_LIMIT_EXCEEDED)
            }
            QueryInput(id, sql)
        }
    }

    private fun profiles(node: JsonNode?, queries: List<QueryInput>): List<Profile> {
        val values = array(node, Location("profiles"), 3)
        val ids = mutableSetOf<String>()
        val profiles = values.mapIndexed { index, entry ->
            val location = Location("profiles[$index]")
            val value = objectNode(entry, location, setOf("id", "weight", "calls_per_hour"))
            val id = string(value.get("id"), location.child("id"))
            val profileLocation = location.copy(profileId = id)
            if (!ids.add(id)) invalid(profileLocation.child("id"), "Profile IDs must be unique.")
            val weight = number(value.get("weight"), profileLocation.child("weight"), positive = true)
            val frequencies = readFrequencies(value.get("calls_per_hour"), profileLocation.child("calls_per_hour"), queries)
            Profile(id, weight, frequencies)
        }
        for (query in queries) {
            if (profiles.none { it.callsPerHour.getValue(query.id) > 0.0 }) {
                invalid(Location("profiles", queryId = query.id), "Each query must be used by at least one profile.")
            }
        }
        return profiles
    }

    private fun readFrequencies(node: JsonNode?, location: Location, queries: List<QueryInput>): Map<String, Double> {
        if (node == null || !node.isObject) invalid(location, "Expected an object with all query IDs.")
        val queryIds = queries.map { it.id }.toSet()
        val keys = node.properties().map { it.key }.toSet()
        val missing = queryIds.firstOrNull { it !in keys }
        if (missing != null) invalid(location.copy(queryId = missing), "Missing query ID in calls_per_hour.")
        val unknown = keys.firstOrNull { it !in queryIds }
        if (unknown != null) invalid(location.copy(queryId = unknown), "Unknown query ID in calls_per_hour.")
        val frequencies = queries.associate { query ->
            query.id to number(node.get(query.id), location.copy(queryId = query.id))
        }
        if (frequencies.values.none { it > 0.0 }) {
            invalid(location, "Each profile must use at least one query with a positive frequency.")
        }
        return frequencies
    }

    private fun writeRates(node: JsonNode?): Map<String, TableWriteRates>? {
        if (node == null || node.isNull) return null
        val location = Location("table_write_rates", code = ErrorCode.INVALID_WRITE_PROFILE)
        if (!node.isObject || node.isEmpty) invalid(location, "Expected a nonempty complete write profile.")
        return node.properties().mapIndexed { index, entry ->
            // Диагностика указывает позицию, не копирует произвольное имя таблицы из невалидного JSON.
            val tableLocation = location.copy(field = "table_write_rates[$index]")
            if (entry.key.isBlank()) invalid(tableLocation, "Table name must not be blank.")
            val value = objectNode(entry.value, tableLocation, setOf(
                "inserts_per_hour", "updates_per_hour", "deletes_per_hour", "source",
            ))
            if (value.has("source") && string(value.get("source"), tableLocation.child("source")) != "manual") {
                invalid(tableLocation.child("source"), "Only manual write rates are supported.")
            }
            entry.key to TableWriteRates(
                insertsPerHour = number(value.get("inserts_per_hour"), tableLocation.child("inserts_per_hour")),
                updatesPerHour = number(value.get("updates_per_hour"), tableLocation.child("updates_per_hour")),
                deletesPerHour = number(value.get("deletes_per_hour"), tableLocation.child("deletes_per_hour")),
            )
        }.toMap()
    }

    private fun provenance(node: JsonNode?, queries: List<QueryInput>, profiles: List<Profile>): WorkloadProvenance? {
        if (node == null || node.isNull) return null
        val location = Location("provenance")
        val value = objectNode(node, location, setOf(
            "schema_version", "source", "source_id", "selection_rule", "windows", "query_examples",
        ))
        if (integer(value.get("schema_version"), location.child("schema_version")) != 1L) {
            invalid(location.child("schema_version"), "Expected provenance schema_version: 1.",
                ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        }
        if (string(value.get("source"), location.child("source")) != "pg_stat_statements") {
            invalid(location.child("source"), "Expected pg_stat_statements.")
        }
        val sourceIdText = string(value.get("source_id"), location.child("source_id"))
        val sourceId = try { UUID.fromString(sourceIdText) } catch (_: IllegalArgumentException) {
            invalid(location.child("source_id"), "Expected a source UUID, never a connection URL.")
        }
        if (!sourceId.toString().equals(sourceIdText, ignoreCase = true)) {
            invalid(location.child("source_id"), "Expected a canonical source UUID.")
        }
        val rule = string(value.get("selection_rule"), location.child("selection_rule"))
        if (rule.length > 1000) invalid(location.child("selection_rule"), "Selection rule must not exceed 1000 characters.")
        val windows = array(value.get("windows"), location.child("windows"), 3).mapIndexed { index, entry ->
            val at = location.child("windows[$index]")
            val window = objectNode(entry, at, setOf("profile_id", "started_at", "finished_at", "complete", "coverage"))
            val profileId = string(window.get("profile_id"), at.child("profile_id"))
            val startedAt = instant(window.get("started_at"), at.child("started_at"))
            val finishedAt = instant(window.get("finished_at"), at.child("finished_at"))
            if (finishedAt <= startedAt) invalid(at.child("finished_at"), "Observation interval must be positive.")
            val complete = window.get("complete")
            if (complete == null || !complete.isBoolean) invalid(at.child("complete"), "Expected a boolean.")
            ObservationWindow(profileId, startedAt, finishedAt, complete.booleanValue(),
                coverage(window.get("coverage"), at.child("coverage")))
        }
        if (windows.map { it.profileId }.toSet() != profiles.map { it.id }.toSet() || windows.size != profiles.size) {
            invalid(location.child("windows"), "Expected exactly one observation window per workload profile.")
        }
        val examples = array(value.get("query_examples"), location.child("query_examples"), 20).mapIndexed { index, entry ->
            val at = location.child("query_examples[$index]")
            val example = objectNode(entry, at, setOf("query_id", "source"))
            val source = when (string(example.get("source"), at.child("source"))) {
                "user_supplied_literals" -> QueryExampleSource.USER_SUPPLIED_LITERALS
                "original_literal_sql" -> QueryExampleSource.ORIGINAL_LITERAL_SQL
                else -> invalid(at.child("source"), "Expected user_supplied_literals or original_literal_sql.")
            }
            QueryExample(string(example.get("query_id"), at.child("query_id")), source)
        }
        if (examples.map { it.queryId }.toSet() != queries.map { it.id }.toSet() || examples.size != queries.size) {
            invalid(location.child("query_examples"), "Expected exactly one example origin per workload query.")
        }
        return WorkloadProvenance(sourceId, rule, windows, examples)
    }

    private fun instant(node: JsonNode?, location: Location): Instant = try {
        Instant.parse(string(node, location))
    } catch (_: DateTimeParseException) {
        invalid(location, "Expected an ISO-8601 timestamp with UTC offset.")
    }

    private fun coverage(node: JsonNode?, location: Location): ObservationCoverage {
        val value = objectNode(node, location, setOf(
            "denominator", "selected_calls", "available_calls", "selected_exec_time_millis", "available_exec_time_millis",
        ))
        if (string(value.get("denominator"), location.child("denominator")) != "available_complete_entries") {
            invalid(location.child("denominator"), "Expected available_complete_entries.")
        }
        fun count(name: String): Long? {
            val entry = value.get(name) ?: invalid(location.child(name), "Value is required; use null for unknown.")
            if (entry.isNull) return null
            val count = integer(entry, location.child(name))
            if (count < 0) invalid(location.child(name), "Count must be nonnegative.")
            return count
        }
        fun time(name: String): Double? {
            val entry = value.get(name) ?: invalid(location.child(name), "Value is required; use null for unknown.")
            return if (entry.isNull) null else number(entry, location.child(name))
        }
        val selectedCalls = count("selected_calls")
        val availableCalls = count("available_calls")
        val selectedTime = time("selected_exec_time_millis")
        val availableTime = time("available_exec_time_millis")
        if (selectedCalls != null && availableCalls != null && selectedCalls > availableCalls ||
            selectedTime != null && availableTime != null && selectedTime > availableTime) {
            invalid(location, "Selected observations cannot exceed available observations.")
        }
        return ObservationCoverage(selectedCalls, availableCalls, selectedTime, availableTime)
    }

    private fun objectNode(node: JsonNode?, location: Location, allowed: Set<String>): JsonNode {
        if (node == null || !node.isObject) invalid(location, "Expected an object.")
        if (node.properties().any { it.key !in allowed }) invalid(location, "Unknown field in object.")
        return node
    }

    private fun array(node: JsonNode?, location: Location, maxSize: Int): List<JsonNode> {
        if (node == null || !node.isArray || node.isEmpty) invalid(location, "Expected a nonempty array.")
        if (node.size() > maxSize) invalid(location, "Array must contain at most $maxSize entries.", ErrorCode.INPUT_LIMIT_EXCEEDED)
        return node.toList()
    }

    private fun string(node: JsonNode?, location: Location): String {
        if (node == null || !node.isString) invalid(location, "Expected a nonblank string.")
        val value = node.stringValue()
        if (value.isBlank()) invalid(location, "Expected a nonblank string.")
        try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value))
        } catch (_: CharacterCodingException) {
            invalid(location, "String must contain valid Unicode.")
        }
        return value
    }

    private fun integer(node: JsonNode?, location: Location): Long {
        if (node == null || !node.isIntegralNumber || !node.canConvertToLong()) {
            invalid(location, "Expected an integer representable as a signed 64-bit value.")
        }
        return node.longValue()
    }

    private fun number(node: JsonNode?, location: Location, positive: Boolean = false): Double {
        if (node == null || !node.isNumber) invalid(location, "Expected a finite number.")
        val decimal = node.decimalValue()
        if (decimal.signum() < 0 || (positive && decimal.signum() == 0)) {
            invalid(location, if (positive) "Number must be positive." else "Number must be nonnegative.")
        }
        val value = decimal.toDouble()
        if (!value.isFinite()) invalid(location, "Number must be finite and representable as Double.")
        if (decimal.signum() > 0 && value == 0.0) {
            invalid(location, "Positive number underflows Double; rescale the input.", ErrorCode.NUMERIC_ERROR)
        }
        return value
    }

    private data class Location(
        val field: String,
        val queryId: String? = null,
        val profileId: String? = null,
        val code: ErrorCode = ErrorCode.INVALID_INPUT,
    ) {
        fun child(name: String) = copy(field = "$field.$name")
    }

    private fun invalid(location: Location, message: String, code: ErrorCode = location.code): Nothing =
        throw AnalysisException(AnalysisError(
            code = code,
            message = message,
            queryId = location.queryId,
            profileId = location.profileId,
            details = mapOf("field" to location.field),
        ))

    companion object {
        private const val MAX_INPUT_BYTES = 1024 * 1024
        private const val MAX_SQL_BYTES = 64 * 1024
    }
}
