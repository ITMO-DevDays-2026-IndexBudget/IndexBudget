package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.candidates.CandidateScope
import ru.itmo.indexbudget.candidates.CandidateVariant
import ru.itmo.indexbudget.domain.*

/** Framework-independent assertions: downstream tests can reuse the same oracle with real SQL resolution. */
object CandidateGenerationContract {
    fun assertScopes(scenario: CandidateGenerationScenario, actual: List<CandidateScope>) {
        check(actual.size == scenario.expectedScopes.size) { "${scenario.name}: unexpected scope count ${actual.size}" }
        scenario.expectedScopes.zip(actual).forEach { (expected, scope) ->
            val label = "${scenario.name}: ${expected.queryId}/${expected.table}"
            assertScopeSummary(expected, scope, label)
            assertVariants(expected.variants, scope, label)
            assertTruncationDiagnostics(scope, label)
        }
    }

    private fun assertScopeSummary(expected: ExpectedCandidateScope, scope: CandidateScope, label: String) {
        check(scope.queryId == expected.queryId && scope.table == expected.table) { "$label: wrong scope identity/order" }
        check(scope.retainedColumns == expected.retainedColumns) { "$label: wrong column shortlist ${scope.retainedColumns}" }
        check(scope.excludedColumns == expected.excludedColumns) { "$label: wrong excluded columns ${scope.excludedColumns}" }
        check(scope.discoveredColumns == expected.retainedColumns.size + expected.excludedColumns.size) {
            "$label: wrong discovered count ${scope.discoveredColumns}"
        }
        check(scope.enumeratedDefinitions == expected.enumeratedDefinitions) {
            "$label: wrong enumeration count ${scope.enumeratedDefinitions}"
        }
        check(scope.retainedColumns.size <= 16 && scope.enumeratedDefinitions <= 976) {
            "$label: generation limit exceeded: ${scope.retainedColumns.size} columns, ${scope.enumeratedDefinitions} definitions"
        }
    }

    private fun assertVariants(expected: Set<CandidateVariant>?, scope: CandidateScope, label: String) {
        check(scope.variants.map { it.definition }.distinct().size == scope.variants.size) {
            "$label: repeated definition in ${scope.variants}"
        }
        expected?.let { check(scope.variants.toSet() == it) { "$label: wrong variants ${scope.variants}" } }
        for (variant in scope.variants) {
            val definition = variant.definition
            check(variant.sourceQueryIds == setOf(scope.queryId) && variant.generationReasons.isNotEmpty()) {
                "$label: expected scope query ID and nonempty generation reasons for $definition; got $variant"
            }
            check(definition.table == scope.table && definition.method == "btree" && !definition.unique) {
                "$label: expected a nonunique B-tree on ${scope.table}; got $definition"
            }
            check(definition.keys.size in 1..2) {
                "$label: expected one or two index keys; got $definition"
            }
            check(definition.keys.all { it.column in scope.retainedColumns && it.column.table == scope.table }) {
                "$label: expected all keys in the retained columns of ${scope.table}; got $definition"
            }
            check(definition.keys.map { it.column }.distinct().size == definition.keys.size) {
                "$label: expected distinct physical columns in index keys; got $definition"
            }
        }
    }

    private fun assertTruncationDiagnostics(scope: CandidateScope, label: String) {
        if (scope.excludedColumns.isEmpty()) {
            check(scope.decisions.isEmpty() && scope.warnings.isEmpty()) { "$label: false truncation" }
            return
        }
        check(scope.decisions.size == 1 && scope.warnings.size == 1) {
            "$label: expected one truncation decision and warning; got ${scope.decisions} and ${scope.warnings}"
        }
        val decision = scope.decisions.single()
        check(decision.reason == CandidateReason.COLUMN_SHORTLIST_TRUNCATED && decision.queryId == scope.queryId) {
            "$label: expected COLUMN_SHORTLIST_TRUNCATED for the scope query; got $decision"
        }
        check(decision.table == scope.table && decision.phase == AnalysisPhase.CANDIDATES) {
            "$label: expected a decision for the scope table in CANDIDATES; got $decision"
        }
        check(decision.details["discovered_columns"] == scope.discoveredColumns.toString()) {
            "$label: expected discovered_columns=${scope.discoveredColumns}; got ${decision.details}"
        }
        check(decision.details["retained_columns"] == scope.retainedColumns.size.toString()) {
            "$label: expected retained_columns=${scope.retainedColumns.size}; got ${decision.details}"
        }
        check(scope.warnings.single().code == WarningCode.COLUMN_SHORTLIST_TRUNCATED) {
            "$label: expected COLUMN_SHORTLIST_TRUNCATED warning; got ${scope.warnings}"
        }
    }
}
