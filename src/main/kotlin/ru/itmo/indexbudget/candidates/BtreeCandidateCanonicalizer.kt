package ru.itmo.indexbudget.candidates

import ru.itmo.indexbudget.domain.*

/** A single scope after canonical deduplication and before the local limit in #12. */
data class CanonicalCandidateScope(
    val candidates: List<IndexCandidate>,
    /** Includes structures subsequently excluded as exact existing duplicates. */
    val deduplicatedDefinitions: Int,
    /** Includes the incoming column-limit decisions and exact duplicate decisions. */
    val decisions: List<CandidateDecision>,
)

/** One instance per run; no estimates, SQL execution or shortlist policy. */
class BtreeCandidateCanonicalizer(
    catalog: CatalogSnapshot,
    private val context: RunContext,
) {
    private val tables = buildMap {
        for (table in catalog.tables) {
            context.checkDeadline()
            require(put(table.ref, table) == null) { "Repeated catalog table" }
        }
    }
    private val columns = tables.mapValues { (_, table) ->
        buildMap {
            for (column in table.columns) {
                context.checkDeadline()
                require(put(column.name, column) == null) { "Repeated catalog column" }
            }
        }
    }
    private val existingByTable = mutableMapOf<TableRef, Map<IndexDefinition, List<String>>>()
    private val renderer = PostgresDdlRenderer()

    fun canonicalize(definition: IndexDefinition): IndexDefinition {
        context.checkDeadline()
        val table = tables[definition.table] ?: catalogError("Candidate table is absent from catalog")
        if (table.kind != TableKind.ORDINARY) catalogError("Candidate requires an ordinary table")
        for (column in definition.includedColumns) {
            context.checkDeadline()
            if (column.name !in columns.getValue(table.ref)) catalogError("INCLUDE column is absent from catalog")
        }
        val resolved = definition.copy(keys = definition.keys.map { key ->
            context.checkDeadline()
            val column = columns.getValue(table.ref)[key.column.name]
                ?: catalogError("Candidate column is absent from catalog")
            key.copy(
                collation = key.collation ?: column.collation,
                operatorClass = key.operatorClass ?: column.defaultOperatorClass
                    ?: catalogError("Default B-tree operator class is unavailable"),
            )
        })
        return CanonicalIndex.normalize(resolved).also { context.checkDeadline() }
    }

    fun canonicalize(scope: CandidateScope): CanonicalCandidateScope {
        context.checkDeadline()
        val grouped = linkedMapOf<IndexDefinition, IndexCandidate>()
        for (variant in scope.variants) {
            context.checkDeadline()
            require(variant.definition.table == scope.table) { "Variant must belong to its scope" }
            require(scope.queryId in variant.sourceQueryIds) { "Variant must retain its source query" }
            val definition = canonicalize(variant.definition)
            val candidate = IndexCandidate(
                id = CanonicalIndex.id(definition),
                definition = definition,
                sourceQueryIds = variant.sourceQueryIds.toSortedSet(),
                generationReasons = variant.generationReasons.sortedBy { it.ordinal }.toSet(),
                ddl = renderer.render(definition),
            )
            add(grouped, candidate)
        }
        val existing = existingByTable.getOrPut(scope.table) { existingDefinitions(scope.table) }
        val decisions = scope.decisions.toMutableList()
        val retained = grouped.values.sortedBy { it.id }.filter { candidate ->
            context.checkDeadline()
            val duplicates = existing[candidate.definition]
            if (duplicates != null) {
                // One decision per matching real index; names remain structured text, never SQL.
                for (name in duplicates) decisions += CandidateDecision(
                    reason = CandidateReason.EXACT_EXISTING_DUPLICATE,
                    candidateId = candidate.id,
                    queryId = scope.queryId,
                    table = scope.table,
                    phase = AnalysisPhase.CANDIDATES,
                    details = mapOf("existing_index" to name),
                )
            }
            duplicates == null
        }
        context.checkDeadline()
        return CanonicalCandidateScope(retained, grouped.size, decisions)
    }

    /** Merge only local survivors, after #12 has applied its limit of 32 per scope. */
    fun merge(candidates: Iterable<IndexCandidate>): List<IndexCandidate> {
        context.checkDeadline()
        val grouped = linkedMapOf<IndexDefinition, IndexCandidate>()
        for (candidate in candidates) {
            context.checkDeadline()
            require(candidate.estimatedBytes == null && candidate.budgetedBytes == null && candidate.writeUnitsPerHour == null) {
                "Merge local candidates before requesting estimates"
            }
            val definition = canonicalize(candidate.definition)
            require(candidate.id == CanonicalIndex.id(definition)) { "Expected a canonical candidate ID" }
            add(grouped, candidate.copy(definition = definition, ddl = renderer.render(definition)))
        }
        return grouped.values.sortedBy { it.id }.also { context.checkDeadline() }
    }

    private fun add(grouped: MutableMap<IndexDefinition, IndexCandidate>, candidate: IndexCandidate) {
        val previous = grouped[candidate.definition]
        grouped[candidate.definition] = candidate.copy(
            sourceQueryIds = (previous?.sourceQueryIds.orEmpty() + candidate.sourceQueryIds).toSortedSet(),
            generationReasons = (previous?.generationReasons.orEmpty() + candidate.generationReasons)
                .sortedBy { it.ordinal }.toSet(),
        )
    }

    private fun existingDefinitions(table: TableRef): Map<IndexDefinition, List<String>> {
        val result = mutableMapOf<IndexDefinition, MutableList<String>>()
        for (index in tables[table]?.existingIndexes.orEmpty()) {
            context.checkDeadline()
            if (index.table != table || index.method != "btree" || !index.valid || !index.ready || !index.accessible ||
                index.predicate != null || index.expressions.isNotEmpty() || index.includedColumns.size > 2 ||
                index.includedColumns.distinct().size != index.includedColumns.size ||
                index.includedColumns.any { it.table != table || it.name !in columns.getValue(table) || it in index.keys.map(IndexKey::column) } ||
                index.keys.size !in 1..2 || index.keys.map { it.column }.distinct().size != index.keys.size
            ) continue
            // Missing metadata is not evidence of equality. Never substitute defaults for real keys.
            if (index.keys.any { key ->
                key.column.table != table || key.operatorClass == null ||
                    key.column.name !in columns.getValue(table) ||
                    (key.collation == null && columns.getValue(table).getValue(key.column.name).collation != null)
            }) continue
            val definition = CanonicalIndex.normalize(IndexDefinition(table, index.keys, includedColumns = index.includedColumns))
            result.getOrPut(definition) { mutableListOf() }.add(index.name)
        }
        return result.mapValues { (_, names) -> names.distinct().sorted() }
    }

    private fun catalogError(message: String): Nothing = throw AnalysisException(AnalysisError(ErrorCode.CATALOG_ERROR, message))
}
