package ru.itmo.indexbudget.application

import ru.itmo.indexbudget.domain.*
import java.security.MessageDigest
import java.util.HexFormat
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

/** Canonicalize collection ordering but retain index key order and effective search_path. */
internal object SeriesConditionCapture {
    private val mapper = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()
    fun capture(catalog: CatalogSnapshot, baseline: EvaluationResult, versions: DatabaseVersions,
        sourceFingerprint: String? = null): SeriesConditions {
        val canonical = catalog.copy(
            tables = catalog.tables.sortedWith(compareBy({ it.ref.schema }, { it.ref.name })).map { table ->
                table.copy(columns = table.columns.sortedBy { it.name }, existingIndexes = table.existingIndexes.sortedBy { it.name })
            },
            unresolvedTables = catalog.unresolvedTables.sortedWith(compareBy({ it.schema }, { it.name })),
            warnings = emptyList(),
            semantics = catalog.semantics?.let { semantics -> semantics.copy(
                types = semantics.types.sortedBy { it.oid }, operators = semantics.operators.sortedBy { it.oid },
                implicitCasts = semantics.implicitCasts.sortedWith(compareBy({ it.sourceTypeOid }, { it.targetTypeOid })),
            ) },
        )
        return SeriesConditions(hash(mapper.writeValueAsString(canonical)), baseline.costByQueryId.toSortedMap(),
            hash(mapper.writeValueAsString(baseline.plansByQueryId.toSortedMap().mapValues { it.value.json })), versions, sourceFingerprint)
    }

    private fun hash(text: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)),
    )
}
