package ru.itmo.indexbudget.optimizer

import ru.itmo.indexbudget.domain.*

/** The three ordered queues around one fixed incumbent; they never depend on later improvements. */
internal object RepairNeighborhood {
    fun queues(
        workload: WorkloadInput,
        start: EvaluatedConfiguration,
        candidates: List<IndexCandidate>,
        evaluations: Map<List<String>, EvaluationResult>,
        metrics: MetricsCalculator,
        baseline: EvaluationResult,
        context: RunContext,
    ): List<List<List<IndexCandidate>>> {
        context.checkDeadline()
        val selectedIds = start.evaluation.candidateIds.toSet()
        val selected = candidates.filter { it.id in selectedIds }.sortedBy { it.id }
        require(selected.size == selectedIds.size) { "Repair start must belong to the shortlist" }
        val worstProfile = if (workload.objective == Objective.ROBUST) {
            start.metrics.profiles.filter { it.baselineCost > 0.0 }
                .sortedWith(compareByDescending<ProfileMetrics> { it.ratio }.thenBy { it.profileId })
                .firstOrNull()?.profileId
        } else null
        require(workload.objective != Objective.ROBUST || worstProfile != null) {
            "Repair requires a positive baseline profile"
        }

        val incoming = candidates.filter { it.id !in selectedIds }.map { candidate ->
            context.checkDeadline()
            val singleton = evaluations[listOf(candidate.id)]
            if (singleton == null) RankedCandidate(candidate, null, null)
            else {
                val usage = metrics.assessBudget(listOf(candidate), workload).usage
                val calculated = metrics.calculate(workload, baseline, singleton, usage)
                val benefit = if (worstProfile == null) calculated.baselineMeanCost - calculated.meanCost
                else calculated.profiles.single { it.profileId == worstProfile }.gain
                RankedCandidate(candidate, benefit, usage.budgetedBytes)
            }
        }.sortedWith { left, right ->
            when {
                left.benefit == null && right.benefit == null -> left.candidate.id.compareTo(right.candidate.id)
                left.benefit == null -> 1
                right.benefit == null -> -1
                else -> compareValues(right.benefit, left.benefit).takeIf { it != 0 }
                    ?: compareValues(left.bytes, right.bytes).takeIf { it != 0 }
                    ?: left.candidate.id.compareTo(right.candidate.id)
            }
        }.map { it.candidate }

        val addPair = mutableListOf<List<IndexCandidate>>()
        val replaceSingle = mutableListOf<List<IndexCandidate>>()
        val replaceWithPair = mutableListOf<List<IndexCandidate>>()
        val maxIndexes = workload.constraints.maxIndexes

        // Incoming rank is the first key, and removed ID is the final key for replacements.
        for (candidate in incoming) {
            context.checkDeadline()
            for (removed in selected) {
                context.checkDeadline()
                replaceSingle += (selected.filter { it.id != removed.id } + candidate).sortedBy { it.id }
            }
        }
        for (first in incoming.indices) {
            context.checkDeadline()
            for (second in first + 1 until incoming.size) {
                context.checkDeadline()
                val pair = listOf(incoming[first], incoming[second])
                if (selected.size + 2 <= maxIndexes) addPair += (selected + pair).sortedBy { it.id }
                if (selected.size + 1 <= maxIndexes) {
                    for (removed in selected) {
                        context.checkDeadline()
                        replaceWithPair += (selected.filter { it.id != removed.id } + pair).sortedBy { it.id }
                    }
                }
            }
        }
        context.checkDeadline()
        return listOf(addPair, replaceSingle, replaceWithPair)
    }

    private data class RankedCandidate(val candidate: IndexCandidate, val benefit: Double?, val bytes: Long?)
}
