package ru.itmo.indexbudget.domain

/** Общая нормировка для валидации входа и расчёта метрик; относительные веса не меняются. */
object ProfileWeights {
    /** Непустой список и уникальность ID должны быть проверены на границе входа. */
    fun normalize(profiles: List<Profile>): Map<String, Double> {
        require(profiles.isNotEmpty()) { "At least one profile is required." }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Profile IDs must be unique." }

        val ordered = profiles.sortedBy { it.id }
        ordered.forEach { profile ->
            if (!profile.weight.isFinite() || profile.weight <= 0.0) {
                fail("Profile weight must be positive and finite.", profile.id)
            }
        }
        val total = ordered.sumOf { it.weight }
        if (!total.isFinite()) {
            fail("The sum of profile weights exceeds the supported numeric range.")
        }
        return ordered.associate { profile ->
            val normalized = profile.weight / total
            if (!normalized.isFinite() || normalized <= 0.0) {
                fail("Normalizing profile weights loses a positive weight.", profile.id)
            }
            profile.id to normalized
        }
    }

    private fun fail(message: String, profileId: String? = null): Nothing = throw AnalysisException(
        AnalysisError(
            code = ErrorCode.NUMERIC_ERROR,
            message = message,
            profileId = profileId,
            details = mapOf("field" to "profiles.weight"),
        ),
    )
}
