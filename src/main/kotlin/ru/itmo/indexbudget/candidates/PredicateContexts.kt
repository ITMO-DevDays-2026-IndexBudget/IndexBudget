package ru.itmo.indexbudget.candidates

import java.util.BitSet
import ru.itmo.indexbudget.domain.*

/** Pairwise structural compatibility, O(leaves²) bits/work; never enumerates Boolean assignments. */
internal class PredicateContexts(private val query: ResolvedQuery, private val context: RunContext) {
    private val count = query.predicates.size.also { if (it > ConditionLimits.MAX_COMPLEXITY) limit() }
    private val paths = MutableList(count) { "" }
    private val together = Array(count) { BitSet(count) }
    private var nodes = 0
    private var orNodes = 0

    init {
        context.checkDeadline()
        if (query.predicates.size > ConditionLimits.MAX_COMPLEXITY) limit()
        if (query.condition == null) {
            for (row in together) { context.checkDeadline(); row.set(0, query.predicates.size) }
        } else {
            visit(query.condition, "condition", 1)
            if (paths.any { it.isEmpty() }) invalid("Condition tree must cover every predicate exactly once")
        }
    }

    private fun visit(node: ResolvedCondition, path: String, depth: Int): BitSet {
        context.checkDeadline()
        if (++nodes > ConditionLimits.MAX_NODES || depth > ConditionLimits.MAX_DEPTH) limit()
        if (node is ResolvedCondition.Predicate) {
            if (node.index !in paths.indices || paths[node.index].isNotEmpty()) invalid("Invalid or repeated condition leaf index")
            paths[node.index] = path
            together[node.index].set(node.index)
            return BitSet().apply { set(node.index) }
        }
        val (leftNode, rightNode) = when (node) {
            is ResolvedCondition.And -> node.left to node.right
            is ResolvedCondition.Or -> { orNodes++; node.left to node.right }
            is ResolvedCondition.Predicate -> error("Handled above")
        }
        val left = visit(leftNode, "$path/L", depth + 1)
        val right = visit(rightNode, "$path/R", depth + 1)
        if (node is ResolvedCondition.And) {
            forEach(left) { together[it].or(right) }
            forEach(right) { together[it].or(left) }
        }
        left.or(right)
        return left
    }

    /** Return all contributing leaves for compatible occurrences of two physical column roles. */
    fun pair(first: BitSet, second: BitSet): BitSet {
        val result = BitSet()
        forEach(first) { index ->
            val partners = together[index].clone() as BitSet
            partners.and(second)
            if (!partners.isEmpty) { result.set(index); result.or(partners) }
        }
        return result
    }

    fun branches(leaves: BitSet, includeOrder: Boolean = false): Set<CandidateSourceBranch> {
        if (orNodes == 0) return emptySet()
        val result = sortedSetOf(compareBy<CandidateSourceBranch>({ it.queryId }, { it.conditionPath }))
        forEach(leaves) { result += CandidateSourceBranch(query.id, paths[it]) }
        if (includeOrder) result += CandidateSourceBranch(query.id, "order_by")
        return result
    }

    fun warnings(): List<AnalysisWarning> = if (orNodes == 0) emptyList() else listOf(AnalysisWarning(
        WarningCode.OR_BRANCH_AWARE_GENERATION,
        "OR сохранён деревом: пары используют совместимые ветви, DNF не разворачивается. Это не проверка выполнимости условий и не гарантия индексного плана.",
        queryId = query.id, details = mapOf("or_nodes" to orNodes.toString(), "condition_nodes" to nodes.toString(),
            "predicate_leaves" to query.predicates.size.toString(), "dnf_expanded" to "false"),
    ))

    private fun forEach(bits: BitSet, consume: (Int) -> Unit) {
        var index = bits.nextSetBit(0)
        while (index >= 0) { context.checkDeadline(); consume(index); index = bits.nextSetBit(index + 1) }
    }

    private fun limit(): Nothing = throw AnalysisException(AnalysisError(ErrorCode.INPUT_LIMIT_EXCEEDED,
        "Condition tree exceeds supported bounds", queryId = query.id))

    private fun invalid(message: String): Nothing = throw AnalysisException(AnalysisError(ErrorCode.INVALID_INPUT,
        message, queryId = query.id))
}
