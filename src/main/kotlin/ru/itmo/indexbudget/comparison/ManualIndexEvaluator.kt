package ru.itmo.indexbudget.comparison

import ru.itmo.indexbudget.candidates.BtreeCandidateCanonicalizer
import ru.itmo.indexbudget.candidates.CandidateScope
import ru.itmo.indexbudget.candidates.CandidateVariant
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.postgres.AnalysisSession

/** Uses the existing canonicalizer/evaluator/metrics; cannot mutate SearchResult or the search pool. */
class ManualIndexEvaluator {
    fun prepare(definitions: List<IndexDefinition>, catalog: CatalogSnapshot, workload: ResolvedWorkload,
                context: RunContext): ManualComparison {
        require(definitions.size in 1..5)
        val canonicalizer = BtreeCandidateCanonicalizer(catalog, context)
        val candidates = mutableListOf<IndexCandidate>()
        val decisions = mutableListOf<CandidateDecision>()
        for ((tableRef, definitionsForTable) in definitions.withIndex().groupBy { it.value.table }) {
            context.checkDeadline()
            if (tableRef !in workload.queries.flatMap { it.tables }) invalid(ErrorCode.UNKNOWN_TABLE, "Manual indexes must reference workload tables.")
            val table = catalog.tables.singleOrNull { it.ref == tableRef }
                ?: invalid(ErrorCode.UNKNOWN_TABLE, "Manual index table is unknown.")
            if (table.kind != TableKind.ORDINARY) invalid(ErrorCode.UNSUPPORTED_SQL, "Manual indexes require ordinary tables.")
            for ((definitionIndex, definition) in definitionsForTable) {
                val field = "manual_indexes.$definitionIndex"
                if (definition.method != "btree" || definition.unique || definition.keys.size !in 1..2 ||
                    definition.includedColumns.isNotEmpty())
                    invalid(ErrorCode.INVALID_INPUT, "Expected a nonunique B-tree with one or two columns.")
                for ((keyIndex, key) in definition.keys.withIndex()) {
                    val keyField = "$field.keys.$keyIndex"
                    if (key.column.table != tableRef || table.columns.none { it.name == key.column.name })
                        invalid(ErrorCode.UNKNOWN_COLUMN, "Manual index column is unknown.")
                    val column = table.columns.single { it.name == key.column.name }
                    if (column.defaultOperatorClass == null)
                        invalid(ErrorCode.INVALID_INPUT, "Manual index key has no default B-tree operator class.", keyField)
                    if (key.collation != null || key.operatorClass != null)
                        invalid(ErrorCode.INVALID_INPUT, "Manual indexes use catalog-default collation and operator class.")
                }
            }
            // A synthetic scope supplies provenance only to the shared canonicalizer; source IDs are
            // cleared below because manual indexes must not claim automatic generation from SQL.
            val scope = CandidateScope("manual", tableRef, table.columns.size, emptyList(), emptyList(),
                definitionsForTable.map { CandidateVariant(it.value, setOf("manual"), emptySet()) }, emptyList(), emptyList())
            val canonical = canonicalizer.canonicalize(scope)
            candidates += canonical.candidates.map { it.copy(sourceQueryIds = emptySet()) }
            decisions += canonical.decisions.map { it.copy(queryId = null, phase = null) }
        }
        return ManualComparison(ManualComparisonStatus.NOT_EVALUATED, candidates.sortedBy { it.id }, decisions)
    }

    fun evaluate(prepared: ManualComparison, workload: ResolvedWorkload, baseline: EvaluationResult,
                 session: AnalysisSession, metrics: MetricsCalculator, context: RunContext,
                 save: (ManualComparison) -> Unit): ManualComparison {
        var progress = prepared
        try {
            val sized = mutableListOf<IndexCandidate>()
            for ((index, candidate) in prepared.candidates.withIndex()) {
                context.checkDeadline()
                val bytes = session.estimateSize(candidate, context)
                val estimated = candidate.copy(estimatedBytes = bytes)
                val usage = metrics.assessBudget(listOf(estimated), workload.input).usage
                sized += estimated.copy(budgetedBytes = usage.budgetedBytes, writeUnitsPerHour = usage.writeUnitsPerHour)
                progress = progress.copy(candidates = sized.toList() + prepared.candidates.drop(index + 1))
                save(progress)
            }
            val budget = metrics.assessBudget(sized, workload.input)
            progress = progress.copy(budgetViolations = budget.violations)
            save(progress)
            context.checkDeadline()
            // An over-budget manual set is still measured, then clearly labelled inadmissible.
            val evaluation = if (sized.isEmpty()) baseline else session.evaluate(sized, workload, context)
            evaluation.requireQueryCoverage(workload.input.queries.map { it.id }.toSet())
            require(evaluation.candidateIds == sized.map { it.id }) { "Manual evaluation returned a different set" }
            val assessment = metrics.assessEvaluation(workload.input, baseline, evaluation, budget.usage)
            progress = progress.copy(status = ManualComparisonStatus.COMPLETED, evaluation = evaluation, metrics = assessment.metrics,
                evaluationViolations = (assessment as? EvaluationAssessment.Rejected)?.violations.orEmpty(),
                queryComparisons = workload.input.queries.map {
                    val before = baseline.costByQueryId.getValue(it.id)
                    val after = evaluation.costByQueryId.getValue(it.id)
                    QueryCostComparison(it.id, before, after, after > before)
                })
            save(progress)
            context.checkDeadline()
            return progress
        } catch (error: AnalysisException) {
            // Preserve a fully completed late evaluation; never retain a partial cost vector.
            if (progress.evaluation == null) progress = progress.copy(status = ManualComparisonStatus.FAILED, error = error.error)
            save(progress)
            if (error.error.code !in setOf(ErrorCode.EXPLAIN_FAILED, ErrorCode.HYPOTHETICAL_INDEX_ERROR)) throw error
            if (error.suppressed.isNotEmpty()) throw error
            context.checkDeadline()
            return progress
        }
    }

    private fun invalid(code: ErrorCode, message: String, field: String? = null): Nothing =
        throw AnalysisException(AnalysisError(code, message, details = field?.let { mapOf("field" to it) }.orEmpty()))
}
