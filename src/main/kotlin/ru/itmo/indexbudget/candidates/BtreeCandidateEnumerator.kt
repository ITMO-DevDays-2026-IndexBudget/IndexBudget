package ru.itmo.indexbudget.candidates

import ru.itmo.indexbudget.domain.*

/** Bounded structural generation from spec v0.2 §5, independent of AST, JDBC and canonical IDs. */
class BtreeCandidateEnumerator : CandidateEnumerator {
    override fun forEachScope(
        workload: ResolvedWorkload,
        context: RunContext,
        consume: (CandidateScope) -> Unit,
    ) {
        context.checkDeadline()
        if (workload.queries.size > 20 || workload.queries.any { it.tables.size > 3 }) {
            throw AnalysisException(AnalysisError(ErrorCode.INPUT_LIMIT_EXCEEDED, "At most 20 queries and 3 tables per query"))
        }
        require(workload.queries.map { it.id }.toSet().size == workload.queries.size) { "Query IDs must be unique" }
        val queries = workload.queries.sortedBy { it.id }
        val featuresByQuery = queries.associate { query ->
            context.checkDeadline()
            query.id to features(query, context)
        }
        val support = columnSupport(workload.input.profiles, featuresByQuery, context)

        for (query in queries) {
            for (table in query.tables.sortedWith(tableOrder)) {
                context.checkDeadline()
                val features = featuresByQuery.getValue(query.id).filterKeys { it.table == table }
                val shortlist = shortlistColumns(query, table, features, support)
                val variants = variants(query, table, shortlist.retainedColumns, features, context)
                context.checkDeadline()
                consume(CandidateScope(
                    queryId = query.id,
                    table = table,
                    discoveredColumns = features.size,
                    retainedColumns = shortlist.retainedColumns,
                    excludedColumns = shortlist.excludedColumns,
                    variants = variants,
                    decisions = shortlist.decisions,
                    warnings = shortlist.warnings,
                ))
                // The synchronous consumer may also spend the remaining analysis budget.
                context.checkDeadline()
            }
        }
    }

    private data class Features(
        var equality: Boolean = false,
        var range: Boolean = false,
        val directions: MutableSet<SortDirection> = linkedSetOf(),
    )

    private data class ColumnShortlist(
        val retainedColumns: List<ColumnRef>,
        val excludedColumns: List<ColumnRef>,
        val decisions: List<CandidateDecision>,
        val warnings: List<AnalysisWarning>,
    )

    private fun shortlistColumns(
        query: ResolvedQuery,
        table: TableRef,
        features: Map<ColumnRef, Features>,
        support: Map<ColumnRef, Double>,
    ): ColumnShortlist {
        val orderByColumns = query.orderBy.map { it.column }.filter { it.table == table }.distinct()
        val otherColumns = features.keys.filter { it !in orderByColumns }.sortedWith(
            compareByDescending<ColumnRef> { support.getValue(it) }
                .thenByDescending { features.getValue(it).equality }
                .then(columnOrder),
        )
        val retained = (orderByColumns + otherColumns).take(COLUMN_LIMIT)
        val excluded = (features.keys - retained.toSet()).sortedWith(columnOrder)
        val details = mapOf(
            "schema" to table.schema,
            "table" to table.name,
            "discovered_columns" to features.size.toString(),
            "retained_columns" to retained.size.toString(),
        )
        val decisions = if (excluded.isEmpty()) emptyList() else listOf(
            CandidateDecision(
                reason = CandidateReason.COLUMN_SHORTLIST_TRUNCATED,
                queryId = query.id,
                table = table,
                phase = AnalysisPhase.CANDIDATES,
                details = details,
            ),
        )
        val warnings = if (excluded.isEmpty()) emptyList() else listOf(
            AnalysisWarning(
                code = WarningCode.COLUMN_SHORTLIST_TRUNCATED,
                message = "Column shortlist truncated before pair generation; see excludedColumns in scope statistics",
                queryId = query.id,
                details = details,
            ),
        )
        return ColumnShortlist(
            retainedColumns = retained,
            excludedColumns = excluded,
            decisions = decisions,
            warnings = warnings,
        )
    }

    private fun features(query: ResolvedQuery, context: RunContext): Map<ColumnRef, Features> {
        val result = linkedMapOf<ColumnRef, Features>()
        fun feature(column: ColumnRef): Features {
            require(column.table in query.tables) { "Feature must reference a resolved query table" }
            return result.getOrPut(column) { Features() }
        }
        for (predicate in query.predicates) {
            context.checkDeadline()
            for (column in listOfNotNull(predicate.leftColumn, predicate.rightColumn)) {
                val value = feature(column)
                if (predicate.operator.equalityFeature) value.equality = true else value.range = true
            }
        }
        for (order in query.orderBy) {
            context.checkDeadline()
            feature(order.column).directions += order.direction
        }
        return result
    }

    private fun columnSupport(
        profiles: List<Profile>,
        features: Map<String, Map<ColumnRef, Features>>,
        context: RunContext,
    ): Map<ColumnRef, Double> {
        require(profiles.size in 1..3) { "Expected 1–3 validated profiles" }
        val maximum = mutableMapOf<ColumnRef, Double>()
        for (profile in profiles.sortedBy { it.id }) {
            context.checkDeadline()
            val support = columnSupportForProfile(profile, features, context)
            for ((column, value) in support) maximum[column] = maxOf(maximum[column] ?: 0.0, value)
        }
        return maximum
    }

    private fun columnSupportForProfile(
        profile: Profile,
        features: Map<String, Map<ColumnRef, Features>>,
        context: RunContext,
    ): Map<ColumnRef, Double> {
        val frequencies = normalizedFrequencies(profile, features.keys, context)
        val support = mutableMapOf<ColumnRef, Double>()
        for ((queryId, normalized) in frequencies) {
            context.checkDeadline()
            for (column in features.getValue(queryId).keys) {
                val sum = (support[column] ?: 0.0) + normalized
                if (!sum.isFinite()) numericError(profile.id, "Column support is not finite")
                support[column] = sum
            }
        }
        return support
    }

    private fun variants(
        query: ResolvedQuery,
        table: TableRef,
        retained: List<ColumnRef>,
        features: Map<ColumnRef, Features>,
        context: RunContext,
    ): List<CandidateVariant> {
        val result = mutableListOf<CandidateVariant>()
        val columns = retained.sortedWith(columnOrder)
        fun emit(keys: List<IndexKey>, reasons: Set<GenerationReason>) {
            context.checkDeadline()
            result += CandidateVariant(IndexDefinition(table, keys), setOf(query.id), reasons)
        }
        for (column in columns) emit(listOf(IndexKey(column)), setOf(GenerationReason.SINGLE_COLUMN))
        for (first in columns) {
            for (second in columns) {
                context.checkDeadline()
                if (first == second) continue
                val reasons = generationReasonsForPair(query, first, second, features)
                if (reasons.isEmpty()) continue
                val firstFeatures = features.getValue(first)
                val secondFeatures = features.getValue(second)
                val firstDirections = firstFeatures.directions.ifEmpty { setOf(SortDirection.ASC) }.sortedBy { it.ordinal }
                val secondDirections = secondFeatures.directions.ifEmpty { setOf(SortDirection.ASC) }.sortedBy { it.ordinal }
                for (firstDirection in firstDirections) {
                    for (secondDirection in secondDirections) {
                        emit(listOf(IndexKey(first, firstDirection), IndexKey(second, secondDirection)), reasons.toSet())
                    }
                }
            }
        }
        return result
    }

    private fun generationReasonsForPair(
        query: ResolvedQuery,
        first: ColumnRef,
        second: ColumnRef,
        features: Map<ColumnRef, Features>,
    ): Set<GenerationReason> = buildSet {
        val firstFeatures = features.getValue(first)
        val secondFeatures = features.getValue(second)
        if (query.orderBy.size == 2 && query.orderBy[0].column == first && query.orderBy[1].column == second) {
            add(GenerationReason.ORDER_BY_PAIR)
        }
        if (firstFeatures.equality) {
            if (secondFeatures.directions.isNotEmpty()) add(GenerationReason.EQUALITY_AND_ORDER)
            if (secondFeatures.range) add(GenerationReason.EQUALITY_AND_RANGE)
            if (secondFeatures.equality) add(GenerationReason.TWO_EQUALITIES)
        }
    }

    private fun numericError(profileId: String, message: String): Nothing = throw AnalysisException(
        AnalysisError(ErrorCode.NUMERIC_ERROR, message, profileId = profileId),
    )

    private companion object {
        const val COLUMN_LIMIT = 16
        val tableOrder = compareBy<TableRef>({ it.schema }, { it.name })
        val columnOrder = compareBy<ColumnRef>({ it.table.schema }, { it.table.name }, { it.name })
    }
}
