package ru.itmo.indexbudget.application

import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.TableRef

/**
 * Checks the shared write profile only after every SQL table has been resolved.
 * Keys are exactly `schema.name` using physical, case-sensitive names. SQL aliases, quotes and
 * search_path are not interpreted here. If distinct tables have the same key because their names
 * contain dots, a supplied profile is rejected instead of silently assigning both the same rates.
 */
class ResolvedWorkloadValidator {
    fun validate(workload: ResolvedWorkload, context: RunContext) {
        context.checkDeadline()
        val rates = workload.input.tableWriteRates
        if (rates == null) {
            if (workload.input.constraints.writeBudgetUnitsPerHour != null) {
                invalid("An enabled write budget requires a complete write profile.")
            }
            return
        }

        val tablesByKey = linkedMapOf<String, TableRef>()
        for (query in workload.queries) {
            context.checkDeadline()
            for (table in query.tables) {
                context.checkDeadline()
                // Keys contain physical, case-sensitive names from resolution, not SQL aliases.
                val key = "${table.schema}.${table.name}"
                val previous = tablesByKey.putIfAbsent(key, table)
                if (previous != null && previous != table) {
                    invalid("Write profile keys cannot distinguish the resolved table names.")
                }
            }
        }
        for ((index, key) in rates.keys.withIndex()) {
            context.checkDeadline()
            if (key !in tablesByKey) {
                invalid("Write profile contains a table outside the resolved workload.", "table_write_rates[$index]")
            }
        }
        for (key in tablesByKey.keys) {
            context.checkDeadline()
            if (key !in rates) invalid("Write profile must cover every resolved workload table.")
        }
        context.checkDeadline()
    }

    private fun invalid(message: String, field: String = "table_write_rates"): Nothing = throw AnalysisException(
        AnalysisError(ErrorCode.INVALID_WRITE_PROFILE, message, details = mapOf("field" to field)),
    )
}
