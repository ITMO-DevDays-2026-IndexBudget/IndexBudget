package ru.itmo.indexbudget.candidates

import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.Profile
import ru.itmo.indexbudget.domain.RunContext

/** Spec v0.2 §5: query frequencies shared by column support and candidate ranking. */
internal fun normalizedFrequencies(
    profile: Profile,
    queryIds: Set<String>,
    context: RunContext,
): Map<String, Double> {
    context.checkDeadline()
    require(profile.callsPerHour.keys == queryIds) { "Profile must cover exactly the resolved queries" }
    val orderedQueryIds = queryIds.sorted()
    var total = 0.0
    for (id in orderedQueryIds) {
        context.checkDeadline()
        val frequency = profile.callsPerHour.getValue(id)
        require(frequency.isFinite() && frequency >= 0.0) { "Expected validated finite nonnegative frequencies" }
        total += frequency
    }
    if (!total.isFinite()) frequencyError(profile.id, "Frequency sum is not finite")
    require(total > 0.0) { "Expected a positive frequency total" }
    return orderedQueryIds.associateWith { id ->
        context.checkDeadline()
        val frequency = profile.callsPerHour.getValue(id)
        val normalized = frequency / total
        if (frequency > 0.0 && normalized == 0.0) frequencyError(profile.id, "Normalized frequency underflow")
        normalized
    }
}

private fun frequencyError(profileId: String, message: String): Nothing = throw AnalysisException(
    AnalysisError(ErrorCode.NUMERIC_ERROR, message, profileId = profileId),
)
