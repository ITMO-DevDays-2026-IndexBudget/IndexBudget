package ru.itmo.indexbudget.postgres

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import ru.itmo.indexbudget.candidates.DdlRenderer
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.candidates.quoteIdentifier
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.EvaluationResult
import ru.itmo.indexbudget.domain.ExplainPlan
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.TableName
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/** Owns a dedicated physical connection; callers must not use it concurrently or return it to a pool. */
class JdbcAnalysisSession(
    private val connection: Connection,
    private val ddlRenderer: DdlRenderer = PostgresDdlRenderer(),
) : AnalysisSession {
    private val closed = AtomicBoolean()
    @Volatile private var activeContext: RunContext? = null
    private var extensionSchema: String? = null
    private var extensionChecked = false
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()

    override fun readCatalog(tables: Set<TableName>, context: RunContext): CatalogSnapshot {
        enter(context)
        try {
            return JdbcCatalogReader(connection,
                beforeQuery = { run ->
                    configureTimeout(run, run.remainingTime())
                },
            ).readCatalog(tables, context)
        } catch (error: AnalysisException) {
            val mapped = mapExistingError(error, context)
            abortQuietly()
            throw mapped
        } catch (error: SQLException) {
            val mapped = databaseError(error, ErrorCode.CATALOG_ERROR, context)
            abortQuietly()
            throw mapped
        }
    }

    override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long = withHypotheses(context) {
        val oid = createHypothesis(candidate, context)
        query("SELECT ${hypopg(context)}.hypopg_relation_size(?::pg_catalog.oid)", context,
            ErrorCode.HYPOTHETICAL_INDEX_ERROR, bind = { setLong(1, oid) }) { result ->
            if (!result.next()) fail(ErrorCode.HYPOTHETICAL_INDEX_ERROR, "HypoPG did not return an index size")
            val size = result.getLong(1)
            if (result.wasNull() || size < 0) fail(ErrorCode.NUMERIC_ERROR, "HypoPG returned an invalid index size")
            if (result.next()) fail(ErrorCode.HYPOTHETICAL_INDEX_ERROR, "HypoPG returned multiple index sizes")
            size
        }
    }

    override fun evaluate(
        candidates: List<IndexCandidate>,
        workload: ResolvedWorkload,
        context: RunContext,
    ): EvaluationResult {
        enter(context)
        context.reserveEvaluationAttempt()
        val ids = candidates.map { it.id }.sorted()
        val expected = workload.input.queries.map { it.id }.toSet()
        if (ids.distinct().size != ids.size || expected.isEmpty() ||
            workload.queries.size != expected.size || workload.queries.map { it.id }.toSet() != expected) {
            fail(ErrorCode.INTERNAL_ERROR, "Evaluation requires unique candidates and every workload query exactly once")
        }
        val result = withHypotheses(context) {
            for (candidate in candidates.sortedBy { it.id }) createHypothesis(candidate, context)
            val costs = linkedMapOf<String, Double>()
            val plans = linkedMapOf<String, ExplainPlan>()
            for (resolved in workload.queries.sortedBy { it.id }) {
                context.checkDeadline()
                val json = query("EXPLAIN (FORMAT JSON) ${resolved.sql}", context, ErrorCode.EXPLAIN_FAILED,
                    queryId = resolved.id, explain = true) { rows ->
                    if (!rows.next()) fail(ErrorCode.EXPLAIN_FAILED, "EXPLAIN did not return a plan", resolved.id)
                    val value = rows.getString(1)
                        ?: fail(ErrorCode.EXPLAIN_FAILED, "EXPLAIN returned a null plan", resolved.id)
                    if (rows.next()) fail(ErrorCode.EXPLAIN_FAILED, "EXPLAIN returned multiple plans", resolved.id)
                    value
                }
                costs[resolved.id] = rootCost(json, resolved.id, context)
                plans[resolved.id] = ExplainPlan(json)
            }
            context.checkDeadline()
            EvaluationResult(ids, costs, plans).requireQueryCoverage(expected)
        }
        context.checkDeadline()
        context.recordCompletedEvaluation()
        return result
    }

    /** Cleanup completes before an evaluation becomes successful or is counted as complete. */
    private fun <T> withHypotheses(context: RunContext, block: () -> T): T {
        enter(context)
        var primary: Throwable? = null
        try {
            reset(context)
            checkHypopgEnabled(context)
            return block()
        } catch (error: AnalysisException) {
            val safe = sanitize(error)
            primary = safe
            throw safe
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            if (closed.get() || context.isCancelled || context.remainingTime().isZero) {
                abortQuietly()
                if (primary == null) {
                    context.checkDeadline()
                    fail(ErrorCode.DB_CONNECTION_ERROR, "Analysis connection is closed")
                }
            } else {
                try {
                    if (primary != null && !connection.autoCommit) {
                        configureNetworkTimeout(context.remainingTime())
                        context.checkDeadline()
                        connection.rollback()
                        context.checkDeadline()
                    }
                    reset(context)
                } catch (error: Exception) {
                    val cleanup = when (error) {
                        is AnalysisException -> sanitize(error)
                        is SQLException -> databaseError(error, ErrorCode.DB_CONNECTION_ERROR, context)
                        else -> AnalysisException(AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Unable to clean up analysis session"))
                    }
                    abortQuietly()
                    if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    private fun checkHypopgEnabled(context: RunContext) {
        if (extensionChecked) return
        query("SELECT pg_catalog.current_setting('hypopg.enabled')", context, ErrorCode.DB_CONNECTION_ERROR) { rows ->
            if (!rows.next() || rows.getString(1) != "on") {
                fail(ErrorCode.DB_CONNECTION_ERROR, "HypoPG is disabled in the analysis session")
            }
        }
        extensionChecked = true
    }

    private fun reset(context: RunContext) {
        query("SELECT ${hypopg(context)}.hypopg_reset()", context, ErrorCode.DB_CONNECTION_ERROR) { rows ->
            if (!rows.next()) fail(ErrorCode.DB_CONNECTION_ERROR, "HypoPG reset did not complete")
        }
    }

    private fun hypopg(context: RunContext): String {
        extensionSchema?.let { return it }
        val schema = query("""
            SELECT n.nspname FROM pg_catalog.pg_extension e
            JOIN pg_catalog.pg_namespace n ON n.oid OPERATOR(pg_catalog.=) e.extnamespace
            WHERE e.extname OPERATOR(pg_catalog.=) 'hypopg'
        """.trimIndent(), context, ErrorCode.DB_CONNECTION_ERROR) { rows ->
            if (!rows.next()) fail(ErrorCode.DB_CONNECTION_ERROR, "HypoPG extension is not installed")
            val name = rows.getString(1)
                ?: fail(ErrorCode.DB_CONNECTION_ERROR, "HypoPG extension schema is unavailable")
            if (rows.next()) fail(ErrorCode.DB_CONNECTION_ERROR, "HypoPG extension schema is ambiguous")
            quoteIdentifier(name)
        }
        extensionSchema = schema
        return schema
    }

    private fun createHypothesis(candidate: IndexCandidate, context: RunContext): Long {
        context.checkDeadline()
        // Do not trust a stored DDL string: the shared renderer validates and quotes the definition.
        val ddl = try {
            ddlRenderer.render(candidate.definition)
        } catch (_: IllegalArgumentException) {
            fail(ErrorCode.HYPOTHETICAL_INDEX_ERROR, "Invalid hypothetical index definition")
        }
        context.checkDeadline()
        return query("SELECT indexrelid FROM ${hypopg(context)}.hypopg_create_index(?)", context,
            ErrorCode.HYPOTHETICAL_INDEX_ERROR, bind = { setString(1, ddl) }) { rows ->
            if (!rows.next()) fail(ErrorCode.HYPOTHETICAL_INDEX_ERROR, "HypoPG did not create an index")
            val oid = rows.getLong(1)
            if (rows.wasNull() || oid <= 0 || rows.next()) {
                fail(ErrorCode.HYPOTHETICAL_INDEX_ERROR, "HypoPG did not create exactly one index")
            }
            oid
        }
    }

    private fun rootCost(json: String, queryId: String, context: RunContext): Double {
        context.checkDeadline()
        val root = try {
            mapper.readTree(json)
        } catch (_: JacksonException) {
            fail(ErrorCode.EXPLAIN_FAILED, "EXPLAIN returned invalid JSON", queryId)
        }
        context.checkDeadline()
        if (root == null || !root.isArray || root.size() != 1 || root[0]?.get("Plan")?.isObject != true) {
            fail(ErrorCode.EXPLAIN_FAILED, "EXPLAIN returned an invalid plan", queryId)
        }
        val cost = root[0].get("Plan").get("Total Cost")
        if (cost == null || !cost.isNumber) fail(ErrorCode.NUMERIC_ERROR, "Plan Total Cost must be numeric", queryId)
        try {
            val value = cost.doubleValue()
            if (!value.isFinite() || value < 0 || value == 0.0 && cost.decimalValue().signum() != 0) {
                fail(ErrorCode.NUMERIC_ERROR, "Plan Total Cost must be finite and nonnegative without underflow", queryId)
            }
            return value
        } catch (_: JacksonException) {
            fail(ErrorCode.NUMERIC_ERROR, "Plan Total Cost is outside the supported numeric range", queryId)
        }
    }

    private fun <T> query(
        sql: String,
        context: RunContext,
        code: ErrorCode,
        queryId: String? = null,
        explain: Boolean = false,
        bind: PreparedStatement.() -> Unit = {},
        read: (ResultSet) -> T,
    ): T {
        try {
            ensureOpen(context)
            val limit = if (explain) context.explainTimeLimit() else context.remainingTime()
            configureTimeout(context, limit)
            return connection.prepareStatement(sql).use { statement ->
                ensureOpen(context)
                statement.queryTimeout = seconds(limit)
                statement.bind()
                context.checkDeadline()
                if (explain) context.reserveExplainAttempt()
                statement.executeQuery().use { rows ->
                    val result = read(rows)
                    context.checkDeadline()
                    result
                }
            }
        } catch (error: SQLException) {
            throw databaseError(error, code, context, queryId)
        }
    }

    /** Millisecond server timeout complements JDBC's rounded, whole-second timeout. */
    private fun configureTimeout(context: RunContext, limit: Duration) {
        ensureOpen(context)
        val remaining = minOf(limit, context.remainingTime())
        configureNetworkTimeout(context.remainingTime())
        connection.prepareStatement("SELECT pg_catalog.set_config('statement_timeout', ?, false)").use { statement ->
            ensureOpen(context)
            statement.queryTimeout = seconds(context.remainingTime())
            statement.setString(1, "${milliseconds(remaining)}ms")
            context.checkDeadline()
            statement.executeQuery().use { rows ->
                if (!rows.next()) fail(ErrorCode.DB_CONNECTION_ERROR, "Unable to set SQL timeout")
            }
            context.checkDeadline()
        }
    }

    private fun configureNetworkTimeout(limit: Duration) {
        connection.setNetworkTimeout(DIRECT_EXECUTOR, milliseconds(limit))
    }

    private fun enter(context: RunContext) {
        activeContext = context
        try {
            ensureOpen(context)
        } catch (error: AnalysisException) {
            if (context.isCancelled || context.remainingTime().isZero) abortQuietly()
            throw error
        }
    }

    private fun ensureOpen(context: RunContext) {
        context.checkDeadline()
        if (closed.get()) fail(ErrorCode.DB_CONNECTION_ERROR, "Analysis connection is closed")
    }

    private fun databaseError(
        error: SQLException,
        fallback: ErrorCode,
        context: RunContext,
        queryId: String? = null,
    ): AnalysisException {
        try {
            context.checkDeadline()
        } catch (stop: AnalysisException) {
            abortQuietly()
            return stop
        }
        val state = error.sqlState?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) }
        val broken = closed.get() || state?.startsWith("08") == true || state in setOf("57P01", "57P02", "57P03") ||
            try { connection.isClosed } catch (_: SQLException) { true }
        if (broken) abortQuietly()
        val code = if (broken) ErrorCode.DB_CONNECTION_ERROR else fallback
        return AnalysisException(AnalysisError(code, when (code) {
            ErrorCode.DB_CONNECTION_ERROR -> "Analysis connection is unavailable"
            ErrorCode.HYPOTHETICAL_INDEX_ERROR -> "Unable to create or measure hypothetical index"
            ErrorCode.CATALOG_ERROR -> "Unable to read PostgreSQL catalog"
            else -> "Unable to explain workload query"
        }, queryId = queryId, details = state?.let { mapOf("sql_state" to it) } ?: emptyMap()))
    }

    private fun mapExistingError(error: AnalysisException, context: RunContext): AnalysisException {
        try {
            context.checkDeadline()
        } catch (stop: AnalysisException) {
            return stop
        }
        val state = error.error.details["sql_state"]
        return if (state?.startsWith("08") == true || state in setOf("57P01", "57P02", "57P03")) {
            AnalysisException(error.error.copy(code = ErrorCode.DB_CONNECTION_ERROR, message = "Analysis connection is unavailable"))
        } else sanitize(error)
    }

    /** JDBC close failures attached by use must not retain driver messages or connection details. */
    private fun sanitize(error: AnalysisException): AnalysisException {
        if (error.suppressed.isEmpty() && error.cause == null) return error
        return AnalysisException(error.error).also { safe ->
            for (suppressed in error.suppressed) {
                safe.addSuppressed(if (suppressed is AnalysisException) sanitize(suppressed)
                else AnalysisException(AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Unable to release JDBC resource")))
            }
        }
    }

    override fun cancel() {
        activeContext?.cancel()
        // abort closes the socket and interrupts the active SQL without issuing cleanup SQL.
        abortQuietly()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            connection.close()
        } catch (_: SQLException) {
            try { connection.abort(DIRECT_EXECUTOR) } catch (_: SQLException) { /* Best effort after failed close. */ }
            fail(ErrorCode.DB_CONNECTION_ERROR, "Unable to close analysis connection")
        }
    }

    private fun abortQuietly() {
        if (!closed.compareAndSet(false, true)) return
        try {
            connection.abort(DIRECT_EXECUTOR)
        } catch (_: SQLException) {
            try { connection.close() } catch (_: SQLException) { /* The original failure remains primary. */ }
        }
    }

    private fun fail(code: ErrorCode, message: String, queryId: String? = null): Nothing =
        throw AnalysisException(AnalysisError(code, message, queryId = queryId))

    private companion object {
        val DIRECT_EXECUTOR = Executor { it.run() }
        fun milliseconds(duration: Duration): Int = ((duration.toNanos() + 999_999) / 1_000_000)
            .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        fun seconds(duration: Duration): Int = ((duration.toNanos() + 999_999_999) / 1_000_000_000)
            .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }
}
