package ru.itmo.indexbudget.domain

import java.time.Duration

/** Resource presets only: candidate selection and the fixed-S0 search algorithm stay the same. */
enum class AnalysisMode(val limits: RunLimits) {
    FAST(RunLimits(
        commandTimeout = Duration.ofSeconds(30),
        searchTimeout = Duration.ofSeconds(20),
        maxExplainAttempts = 500,
        maxRepairAttempts = 8,
    )),
    NORMAL(RunLimits()),
    DEEP(RunLimits(
        commandTimeout = Duration.ofSeconds(300),
        searchTimeout = Duration.ofSeconds(240),
        maxExplainAttempts = 10_000,
        maxRepairAttempts = 256,
    )),
}
