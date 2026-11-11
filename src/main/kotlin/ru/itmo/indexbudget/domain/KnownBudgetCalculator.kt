package ru.itmo.indexbudget.domain

import java.math.BigDecimal

/** One exact write calculation for both size-free preparation and complete budget assessment. */
internal object KnownBudgetCalculator {
    fun assess(candidates: List<IndexCandidate>, workload: WorkloadInput): KnownBudgetAssessment {
        require(candidates.map { it.id }.distinct().size == candidates.size) { "Candidate IDs must be unique" }
        val rates = workload.tableWriteRates
        if (rates == null && workload.constraints.writeBudgetUnitsPerHour != null) {
            throw invalidWriteProfile("An enabled write budget requires a complete write profile")
        }
        if (rates != null) {
            if (rates.isEmpty()) throw invalidWriteProfile("A supplied write profile must not be empty")
            for (rate in rates.values) {
                if (listOf(rate.insertsPerHour, rate.updatesPerHour, rate.deletesPerHour)
                        .any { !it.isFinite() || it < 0.0 }) {
                    throw invalidWriteProfile("Write rates must be finite and nonnegative")
                }
            }
        }
        var exactUnits: BigDecimal? = if (rates == null) null else BigDecimal.ZERO
        for (candidate in candidates.sortedBy { it.id }) {
            require(candidate.definition.keys.size in 1..2) { "Candidates must have one or two key columns" }
            if (rates != null) {
                val table = candidate.definition.table
                val rate = rates["${table.schema}.${table.name}"]
                    ?: throw invalidWriteProfile("Write profile must cover every candidate table")
                // Preserve exact input Doubles through accumulation and comparison with the limit.
                val rowChanges = BigDecimal(rate.insertsPerHour) + BigDecimal(rate.updatesPerHour) +
                    BigDecimal(rate.deletesPerHour)
                val total = checkNotNull(exactUnits) + rowChanges * BigDecimal(candidate.definition.keys.size + candidate.definition.includedColumns.size)
                if (total > BigDecimal(Double.MAX_VALUE)) {
                    throw AnalysisException(AnalysisError(ErrorCode.NUMERIC_ERROR,
                        "Write units exceed the supported numeric range"))
                }
                exactUnits = total
            }
        }
        val violations = buildSet {
            val limit = workload.constraints.writeBudgetUnitsPerHour
            if (limit != null && checkNotNull(exactUnits) > BigDecimal(limit)) add(BudgetViolation.WRITE_BUDGET)
            if (candidates.size > workload.constraints.maxIndexes) add(BudgetViolation.MAX_INDEXES)
        }
        return KnownBudgetAssessment(exactUnits?.toDouble(), violations)
    }

    private fun invalidWriteProfile(message: String) = AnalysisException(
        AnalysisError(ErrorCode.INVALID_WRITE_PROFILE, message, details = mapOf("field" to "table_write_rates")),
    )
}
