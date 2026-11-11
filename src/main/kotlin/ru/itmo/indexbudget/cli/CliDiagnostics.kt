package ru.itmo.indexbudget.cli

import ru.itmo.indexbudget.domain.*
import tools.jackson.databind.json.JsonMapper
import java.io.PrintWriter

/** Единый формат диагностики обычного анализа, подготовки серии и её независимых точек. */
internal object CliDiagnostics {
    private val mapper = JsonMapper.builder().build()

    fun progress(err: PrintWriter, progress: RunProgress) {
        val counters = progress.statistics
        err.println("stage=${progress.phase.name.lowercase()} explain_attempts=${counters.explainAttempts} " +
            "completed_sets=${counters.completedEvaluations} cache_hits=${counters.cacheHits}" +
            (if (progress.scope == null) "" else " evaluation_attempts=${counters.evaluationAttempts} repair_attempts=${counters.repairAttempts}") +
            scopeFields(progress.scope, progress.ordinal))
    }

    fun limits(
        err: PrintWriter, mode: AnalysisMode, limits: RunLimits, ranking: CandidateRanking? = null,
        stage: String = "validation", scope: RunScope? = null, ordinal: Int? = null,
    ) {
        err.println("stage=$stage analysis_mode=${mode.name.lowercase()} " +
            "command_timeout_millis=${limits.commandTimeout.toMillis()} " +
            "search_timeout_millis=${limits.searchTimeout.toMillis()} " +
            "explain_timeout_millis=${limits.explainTimeout.toMillis()} " +
            "connect_timeout_millis=${limits.connectTimeout.toMillis()} " +
            "max_explain_attempts=${limits.maxExplainAttempts} max_repair_attempts=${limits.maxRepairAttempts}" +
            (ranking?.let { " candidate_ranking=${it.wireName}" } ?: "") + scopeFields(scope, ordinal))
    }

    fun summary(
        err: PrintWriter, mode: AnalysisMode, counters: RunStatistics, status: String,
        stopReasons: List<String>, ranking: CandidateRanking? = null,
        stage: String = "finished", scope: RunScope? = null, ordinal: Int? = null,
        conditionsMatch: Boolean? = null,
    ) {
        err.println("stage=$stage status=$status analysis_mode=${mode.name.lowercase()} " +
            "explain_attempts=${counters.explainAttempts} evaluation_attempts=${counters.evaluationAttempts} " +
            "completed_sets=${counters.completedEvaluations} cache_hits=${counters.cacheHits} " +
            "repair_attempts=${counters.repairAttempts} stop_reasons=${stopReasons.distinct().joinToString(",")}" +
            (ranking?.let { " candidate_ranking=${it.wireName}" } ?: "") + scopeFields(scope, ordinal) +
            (if (scope == RunScope.POINT) " conditions_match=${conditionsMatch ?: "unknown"}" else ""))
    }

    fun error(err: PrintWriter, error: AnalysisError, scope: RunScope? = null, ordinal: Int? = null) {
        val diagnostic = linkedMapOf<String, Any>("code" to error.code.name, "message" to error.message)
        error.queryId?.let { diagnostic["query_id"] = it }
        error.profileId?.let { diagnostic["profile_id"] = it }
        if (error.details.isNotEmpty()) diagnostic["details"] = error.details
        scope?.let { diagnostic["scope"] = it.wireName }
        ordinal?.let { diagnostic["ordinal"] = it }
        // JSON экранирует переводы строк и управляющие символы в пользовательских IDs.
        err.println("${error.code.name}: ${mapper.writeValueAsString(diagnostic)}")
    }

    private fun scopeFields(scope: RunScope?, ordinal: Int?): String =
        (scope?.let { " scope=${it.wireName}" } ?: "") + (ordinal?.let { " ordinal=$it" } ?: "")
}
