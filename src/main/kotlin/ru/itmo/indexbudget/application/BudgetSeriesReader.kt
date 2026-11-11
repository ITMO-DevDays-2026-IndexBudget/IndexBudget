package ru.itmo.indexbudget.application

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.domain.*
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

/** Отдельный версионированный запрос серии; workload v2 сохраняет прежний формат. */
@Component
class BudgetSeriesReader {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()

    fun read(path: Path, context: RunContext? = null): BudgetSeriesRequest {
        context?.checkDeadline()
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_INPUT_BYTES + 1) }
        if (bytes.size > MAX_INPUT_BYTES) invalid("series", "Series JSON must not exceed 64 KiB UTF-8.", ErrorCode.INPUT_LIMIT_EXCEEDED)
        val json = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            invalid("series", "Series JSON must be valid UTF-8.")
        }
        context?.checkDeadline()
        val root = try { mapper.readTree(json) }
        catch (_: JacksonException) { invalid("series", "Invalid series JSON, duplicate keys or trailing content.") }
        if (root == null || !root.isObject) invalid("series", "Expected a series object.")
        if (root.properties().any { it.key !in FIELDS }) invalid("series", "Unknown field in series object.")
        val version = root.get("schema_version")
        if (version == null || !version.isIntegralNumber || !version.canConvertToInt() || version.intValue() != 1) {
            invalid("schema_version", "Expected series schema_version: 1.", ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        }
        val budgetNode = root.get("budget")
        val budget = if (budgetNode?.isString == true) SeriesBudget.entries.firstOrNull { it.wireName == budgetNode.stringValue() } else null
        if (budget == null) invalid("budget", "Expected storage_budget_bytes or write_budget_units_per_hour.")
        val valuesNode = root.get("values")
        if (valuesNode == null || !valuesNode.isArray || valuesNode.size() !in 3..5) {
            invalid("values", "A series requires 3 to 5 distinct budget values.")
        }
        val values = valuesNode.mapIndexed { index, node ->
            context?.checkDeadline()
            val field = "values[$index]"
            if (!node.isNumber) invalid(field, "Expected a nonnegative budget number.")
            if (budget == SeriesBudget.STORAGE && (!node.isIntegralNumber || !node.canConvertToLong())) {
                invalid(field, "Storage budget must be a signed 64-bit integer.")
            }
            val decimal = node.decimalValue()
            if (decimal.signum() < 0) invalid(field, "Budget must be nonnegative.")
            if (budget == SeriesBudget.WRITE) {
                val value = decimal.toDouble()
                if (!value.isFinite()) invalid(field, "Write budget must be finite and representable as Double.")
                if (decimal.signum() > 0 && value == 0.0) {
                    invalid(field, "Positive write budget underflows Double; rescale the input.", ErrorCode.NUMERIC_ERROR)
                }
            }
            decimal
        }
        val distinct = if (budget == SeriesBudget.STORAGE) values.map { it.toLong() }.distinct().size
            else values.map { it.toDouble() }.distinct().size
        if (distinct != values.size) invalid("values", "Series budgets must be distinct after numeric conversion.")
        val timeout = root.get("timeout_seconds")?.let { timeout(it) } ?: 600L
        context?.checkDeadline()
        return BudgetSeriesRequest(budget, values, timeout)
    }

    private fun timeout(node: JsonNode): Long {
        if (!node.isIntegralNumber || !node.canConvertToLong() || node.longValue() !in 1L..86400L) {
            invalid("timeout_seconds", "Series timeout must be an integer from 1 to 86400 seconds.")
        }
        return node.longValue()
    }

    private fun invalid(field: String, message: String, code: ErrorCode = ErrorCode.INVALID_INPUT): Nothing =
        throw AnalysisException(AnalysisError(code, message, details = mapOf("field" to field)))

    companion object {
        private const val MAX_INPUT_BYTES = 64 * 1024
        private val FIELDS = setOf("schema_version", "budget", "values", "timeout_seconds")
    }
}
