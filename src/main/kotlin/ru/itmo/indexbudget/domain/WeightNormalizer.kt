package ru.itmo.indexbudget.domain

/**
 * Normalizes relative profile weights to p_s = weight_s / sum(weight) for the metrics module.
 *
 * Invariants from the specification: every weight is finite and positive, and the normalized weight
 * stays positive and finite. A positive weight lost to underflow is an error, never a zero-probability
 * scenario; a non-positive or non-finite weight sum is a numeric error.
 */
object WeightNormalizer {

    /** Returns profileId -> normalized weight, preserving profile order. */
    fun normalize(profiles: List<Profile>): Map<String, Double> {
        require(profiles.map { it.id }.toSet().size == profiles.size) {
            "Profile IDs must be unique"
        }
        val sum = profiles.sumOf { it.weight }
        if (!sum.isFinite() || sum <= 0.0) {
            throw numericError("Profile weight sum must be finite and positive")
        }
        return profiles.associate { profile ->
            val weight = profile.weight
            if (!weight.isFinite() || weight <= 0.0) {
                throw numericError("Profile '${profile.id}' weight must be finite and positive", profile.id)
            }
            val normalized = weight / sum
            if (!normalized.isFinite() || normalized <= 0.0) {
                throw numericError("Profile '${profile.id}' weight lost to underflow during normalization", profile.id)
            }
            profile.id to normalized
        }
    }

    private fun numericError(message: String, profileId: String? = null): AnalysisException =
        AnalysisException(AnalysisError(ErrorCode.NUMERIC_ERROR, message, profileId = profileId))
}