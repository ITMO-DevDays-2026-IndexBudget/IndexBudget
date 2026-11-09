package ru.itmo.indexbudget.application

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.WorkloadInput
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path

/** JSON boundary only; semantic and SQL validation belongs to the analysis pipeline. */
@Component
class WorkloadReader {
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().enable(KotlinFeature.StrictNullChecks).build())
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .enable(EnumFeature.WRITE_ENUMS_TO_LOWERCASE)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .build()

    fun read(path: Path): WorkloadInput {
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_INPUT_BYTES + 1) }
        if (bytes.size > MAX_INPUT_BYTES) {
            throw AnalysisException(AnalysisError(ErrorCode.INPUT_LIMIT_EXCEEDED, "Workload JSON must not exceed 1 MiB."))
        }
        val tree = mapper.readTree(bytes)
        val version = tree?.get("schema_version")
        if (version == null || !version.isIntegralNumber || !version.canConvertToInt() || version.intValue() != 2) {
            throw AnalysisException(AnalysisError(ErrorCode.UNSUPPORTED_SCHEMA_VERSION, "Expected schema_version: 2."))
        }
        if (tree.get("constraints")?.has("write_budget_units_per_hour") != true) {
            throw AnalysisException(AnalysisError(
                ErrorCode.INVALID_INPUT,
                "constraints.write_budget_units_per_hour is required; use null to disable the write budget.",
            ))
        }
        return mapper.treeToValue(tree, WorkloadInput::class.java)
    }

    companion object {
        private const val MAX_INPUT_BYTES = 1024 * 1024
    }
}
