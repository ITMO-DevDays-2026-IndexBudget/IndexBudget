package ru.itmo.indexbudget.domain

/** Default CLI code applies when there is no usable partial result. */
enum class ErrorCode(val defaultExitCode: Int = 1) {
    INVALID_INPUT(2),
    UNSUPPORTED_SCHEMA_VERSION(2),
    INPUT_LIMIT_EXCEEDED(2),
    UNSUPPORTED_SQL(2),
    UNKNOWN_TABLE(2),
    UNKNOWN_COLUMN(2),
    AMBIGUOUS_COLUMN(2),
    INVALID_WRITE_PROFILE(2),
    OUTPUT_DIRECTORY_NOT_EMPTY(2),
    NUMERIC_ERROR,
    ZERO_BASELINE_REGRESSION,
    DB_CONNECTION_ERROR,
    CATALOG_ERROR,
    STATISTICS_UNAVAILABLE,
    STATISTICS_PERMISSION_DENIED,
    STATISTICS_READ_FAILED,
    HYPOTHETICAL_INDEX_ERROR,
    EXPLAIN_FAILED,
    DEADLINE_EXCEEDED,
    CANCELLED,
    EXPLAIN_LIMIT,
    REPAIR_LIMIT,
    EXPORT_FAILED,
    NOT_IMPLEMENTED,
    INTERNAL_ERROR,
}

/** Public diagnostic. Messages and details must never contain credentials or a connection URL. */
data class AnalysisError(
    val code: ErrorCode,
    val message: String,
    val queryId: String? = null,
    val profileId: String? = null,
    val details: Map<String, String> = emptyMap(),
)

class AnalysisException(val error: AnalysisError, cause: Throwable? = null) :
    RuntimeException(error.message, cause)

enum class WarningCode {
    OR_BRANCH_AWARE_GENERATION,
    MISSING_STATISTICS,
    COLUMN_SHORTLIST_TRUNCATED,
    LOCAL_CANDIDATE_LIMIT,
    INCLUDE_PAYLOAD_LIMIT,
    INCLUDE_VARIANT_LIMIT,
    GLOBAL_CANDIDATE_LIMIT,
    CANDIDATE_REJECTED,
    EVALUATION_FAILED,
}

data class AnalysisWarning(
    val code: WarningCode,
    val message: String,
    val queryId: String? = null,
    val profileId: String? = null,
    val details: Map<String, String> = emptyMap(),
)
