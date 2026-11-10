package ru.itmo.indexbudget.application

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.Constraints
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.Objective
import ru.itmo.indexbudget.domain.Profile
import ru.itmo.indexbudget.domain.ProfileWeights
import ru.itmo.indexbudget.domain.QueryInput
import ru.itmo.indexbudget.domain.TableWriteRates
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

/** Проверяет вход до SQL-парсера и БД. Полнота таблиц write profile проверяется после resolution. */
@Component
class WorkloadReader {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        // Сохраняем десятичное значение до проверки: 1e-400 не должно превратиться в допустимый ноль.
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()

    fun read(path: Path): WorkloadInput {
        val root = readJsonObject(path)
        val version = root.get("schema_version")
        if (version == null || !version.isIntegralNumber || !version.canConvertToInt() || version.intValue() != 2) {
            invalid(Location("schema_version"),
                "Expected schema_version: 2. Migrate v1 frequencies from queries to profiles.calls_per_hour.",
                ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        }
        objectNode(root, Location("workload"), setOf(
            "schema_version", "objective", "queries", "profiles", "constraints", "table_write_rates",
        ))
        val objective = when (val value = root.get("objective")) {
            null -> Objective.ROBUST
            else -> when (string(value, Location("objective"))) {
                "robust" -> Objective.ROBUST
                "mean" -> Objective.MEAN
                else -> invalid(Location("objective"), "Expected robust or mean.")
            }
        }
        val constraints = constraints(root.get("constraints"))
        val queries = queries(root.get("queries"))
        val profiles = profiles(root.get("profiles"), queries)
        ProfileWeights.normalize(profiles)
        val writeRates = writeRates(root.get("table_write_rates"))
        if (constraints.writeBudgetUnitsPerHour != null && writeRates == null) {
            invalid(Location("table_write_rates"), "An enabled write budget requires a complete write profile.",
                ErrorCode.INVALID_WRITE_PROFILE)
        }
        return WorkloadInput(queries, profiles, constraints, objective = objective, tableWriteRates = writeRates)
    }

    private fun readJsonObject(path: Path): JsonNode {
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_INPUT_BYTES + 1) }
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
        val tree = try {
            mapper.readTree(json)
        } catch (_: JacksonException) {
            // Сообщения парсера могут содержать SQL, пароли или другие фрагменты входа.
            invalid(Location("workload"), "Invalid JSON, duplicate object keys or trailing content.")
        }
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
