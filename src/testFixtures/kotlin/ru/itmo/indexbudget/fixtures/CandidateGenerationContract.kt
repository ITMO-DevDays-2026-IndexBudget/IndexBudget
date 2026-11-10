package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.candidates.CandidateScope
import ru.itmo.indexbudget.domain.*

/** Framework-independent assertions: downstream tests can reuse the same oracle with real SQL resolution. */
object CandidateGenerationContract {
    fun assertScopes(scenario: CandidateGenerationScenario, actual: List<CandidateScope>) {
        check(actual.size == scenario.expectedScopes.size) { "${scenario.name}: unexpected scope count ${actual.size}" }
        scenario.expectedScopes.zip(actual).forEach { (expected, scope) ->
            val label = "${scenario.name}: ${expected.queryId}/${expected.table}"
            check(scope.queryId == expected.queryId && scope.table == expected.table) { "$label: wrong scope identity/order" }
            check(scope.retainedColumns == expected.retainedColumns) { "$label: wrong column shortlist ${scope.retainedColumns}" }
            check(scope.excludedColumns == expected.excludedColumns) { "$label: wrong excluded columns" }
            check(scope.discoveredColumns == expected.retainedColumns.size + expected.excludedColumns.size) { "$label: wrong discovered count" }
            check(scope.enumeratedDefinitions == expected.enumeratedDefinitions) { "$label: wrong enumeration count ${scope.enumeratedDefinitions}" }
            check(scope.variants.map { it.definition }.distinct().size == scope.variants.size) { "$label: repeated definition" }
            expected.variants?.let { check(scope.variants.toSet() == it) { "$label: wrong variants ${scope.variants}" } }
            check(scope.retainedColumns.size <= 16 && scope.enumeratedDefinitions <= 976) { "$label: generation limit exceeded" }
            for (variant in scope.variants) {
                check(variant.sourceQueryIds == setOf(scope.queryId) && variant.generationReasons.isNotEmpty()) { "$label: lost provenance" }
                check(variant.definition.table == scope.table && variant.definition.method == "btree" && !variant.definition.unique)
                check(variant.definition.keys.size in 1..2)
                check(variant.definition.keys.all { it.column in scope.retainedColumns && it.column.table == scope.table })
                check(variant.definition.keys.map { it.column }.distinct().size == variant.definition.keys.size)
            }
            if (scope.excludedColumns.isEmpty()) {
                check(scope.decisions.isEmpty() && scope.warnings.isEmpty()) { "$label: false truncation" }
            } else {
                val decision = scope.decisions.single()
                check(decision.reason == CandidateReason.COLUMN_SHORTLIST_TRUNCATED && decision.queryId == scope.queryId)
                check(decision.table == scope.table && decision.phase == AnalysisPhase.CANDIDATES)
                check(decision.details["discovered_columns"] == scope.discoveredColumns.toString())
                check(decision.details["retained_columns"] == scope.retainedColumns.size.toString())
                check(scope.warnings.single().code == WarningCode.COLUMN_SHORTLIST_TRUNCATED)
            }
        }
    }
}
