package ru.itmo.indexbudget.domain

import java.math.BigDecimal

enum class SeriesBudget(val wireName: String) {
    STORAGE("storage_budget_bytes"), WRITE("write_budget_units_per_hour"),
}

data class BudgetSeriesRequest(
    val budget: SeriesBudget,
    val values: List<BigDecimal>,
    val timeoutSeconds: Long = 600,
) {
    fun validate(input: WorkloadInput) {
        fun invalid(message: String): Nothing = throw AnalysisException(AnalysisError(ErrorCode.INVALID_INPUT, message))
        if (values.size !in 3..5 || values.map { it.stripTrailingZeros() }.distinct().size != values.size)
            invalid("A budget series requires 3–5 distinct values.")
        if (timeoutSeconds !in 1..86400) invalid("Series timeout must be between 1 and 86400 seconds.")
        values.forEach { value ->
            if (value.signum() < 0) invalid("Series budgets must be nonnegative.")
            if (budget == SeriesBudget.STORAGE) {
                try { value.longValueExact() } catch (_: ArithmeticException) {
                    invalid("Storage budgets must be integers in the Long range.")
                }
            } else {
                val converted = value.toDouble()
                if (!converted.isFinite() || (value.signum() > 0 && converted == 0.0))
                    invalid("Write budgets must be representable finite numbers.")
            }
        }
        if (budget == SeriesBudget.WRITE) {
            if (input.tableWriteRates == null) invalid("A write budget series requires a complete table_write_rates profile.")
            if (values.map { it.toDouble() }.distinct().size != values.size)
                invalid("Write budget values must remain distinct as Double values.")
        }
    }

    fun inputAt(input: WorkloadInput, value: BigDecimal): WorkloadInput = input.copy(
        constraints = when (budget) {
            SeriesBudget.STORAGE -> input.constraints.copy(storageBudgetBytes = value.longValueExact())
            SeriesBudget.WRITE -> input.constraints.copy(writeBudgetUnitsPerHour = value.toDouble())
        },
    )
}

enum class SeriesStatus(val exitCode: Int) { COMPLETED(0), PARTIAL(3), FAILED(1) }

/** Comparable means these observed facts match, not a frozen PostgreSQL statistics snapshot. */
data class SeriesConditions(
    val catalogHash: String,
    val baselineCosts: Map<String, Double>,
    val baselinePlansHash: String,
    val databaseVersions: DatabaseVersions,
    val sourceFingerprint: String? = null,
)

enum class SeriesPointStatus { COMPLETED, PARTIAL, FAILED, SKIPPED }

data class BudgetSeriesPoint(
    val ordinal: Int,
    val value: BigDecimal,
    val status: SeriesPointStatus,
    val runId: String? = null,
    val result: SearchResult? = null,
    val conditions: SeriesConditions? = null,
    val conditionsMatch: Boolean? = null,
    val changes: List<String> = emptyList(),
    val artifacts: Map<String, String> = emptyMap(),
    val error: AnalysisError? = null,
)

data class BudgetSeriesResult(
    val status: SeriesStatus,
    val points: List<BudgetSeriesPoint>,
    val preparationError: AnalysisError? = null,
) {
    val exitCode: Int get() = status.exitCode
}
