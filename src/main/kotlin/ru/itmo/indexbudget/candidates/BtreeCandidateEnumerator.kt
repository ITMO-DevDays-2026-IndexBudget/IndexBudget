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
                val sortedColumns = query.orderBy.map { it.column }.filter { it.table == table }.distinct()
                val otherColumns = features.keys.filter { it !in sortedColumns }.sortedWith(
                    compareByDescending<ColumnRef> { support.getValue(it) }
                        .thenByDescending { features.getValue(it).equality }
                        .then(columnOrder),
                )
                val retained = (sortedColumns + otherColumns).take(COLUMN_LIMIT)
                val excluded = (features.keys - retained.toSet()).sortedWith(columnOrder)
                val details = mapOf(
                    "schema" to table.schema,
                    "table" to table.name,
                    "discovered_columns" to features.size.toString(),
                    "retained_columns" to retained.size.toString(),
                )
                val decisions = if (excluded.isEmpty()) emptyList() else listOf(
                    CandidateDecision(
                        CandidateReason.COLUMN_SHORTLIST_TRUNCATED,
                        queryId = query.id,
                        table = table,
                        phase = AnalysisPhase.CANDIDATES,
                        details = details,
                    ),
                )
                val warnings = if (excluded.isEmpty()) emptyList() else listOf(
                    AnalysisWarning(
                        WarningCode.COLUMN_SHORTLIST_TRUNCATED,
                        "Column shortlist truncated before pair generation; see excludedColumns in scope statistics",
                        queryId = query.id,
                        details = details,
                    ),
                )
                val variants = variants(query, table, retained, features, context)
                context.checkDeadline()
                consume(CandidateScope(query.id, table, features.size, retained, excluded, variants, decisions, warnings))
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
                if (predicate.operator == ComparisonOperator.EQUAL) value.equality = true else value.range = true
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
            require(profile.callsPerHour.keys == features.keys) { "Profile must cover exactly the resolved queries" }
            val frequencies = features.keys.map { profile.callsPerHour.getValue(it) }
            require(frequencies.all { it.isFinite() && it >= 0.0 } && frequencies.any { it > 0.0 }) {
                "Expected finite nonnegative frequencies with a positive total"
            }
            val total = frequencies.sum()
            if (!total.isFinite()) numericError(profile.id, "Frequency sum is not finite")
            val support = mutableMapOf<ColumnRef, Double>()
            for ((queryId, columns) in features) {
                context.checkDeadline()
                val frequency = profile.callsPerHour.getValue(queryId)
                val normalized = frequency / total
                if (frequency > 0.0 && normalized == 0.0) numericError(profile.id, "Normalized frequency underflow")
                for (column in columns.keys) {
                    val sum = (support[column] ?: 0.0) + normalized
                    if (!sum.isFinite()) numericError(profile.id, "Column support is not finite")
                    support[column] = sum
                }
            }
            for ((column, value) in support) maximum[column] = maxOf(maximum[column] ?: 0.0, value)
        }
        return maximum
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
                val firstFeatures = features.getValue(first)
                val secondFeatures = features.getValue(second)
                val reasons = linkedSetOf<GenerationReason>()
                if (query.orderBy.size == 2 && query.orderBy[0].column == first && query.orderBy[1].column == second) {
                    reasons += GenerationReason.ORDER_BY_PAIR
                }
                if (firstFeatures.equality) {
                    if (secondFeatures.directions.isNotEmpty()) reasons += GenerationReason.EQUALITY_AND_ORDER
                    if (secondFeatures.range) reasons += GenerationReason.EQUALITY_AND_RANGE
                    if (secondFeatures.equality) reasons += GenerationReason.TWO_EQUALITIES
                }
                if (reasons.isEmpty()) continue
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

    private fun numericError(profileId: String, message: String): Nothing = throw AnalysisException(
        AnalysisError(ErrorCode.NUMERIC_ERROR, message, profileId = profileId),
    )

    private companion object {
        const val COLUMN_LIMIT = 16
        val tableOrder = compareBy<TableRef>({ it.schema }, { it.name })
        val columnOrder = compareBy<ColumnRef>({ it.table.schema }, { it.table.name }, { it.name })
    }
}
