package ru.itmo.indexbudget.postgres

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import ru.itmo.indexbudget.candidates.DdlRenderer
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.candidates.quoteIdentifier
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.DatabaseVersions
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
    private var capturedVersions = DatabaseVersions()
    private var capturedSourceFingerprint: String? = null
    override val databaseVersions: DatabaseVersions get() = capturedVersions
    override val sourceFingerprint: String? get() = capturedSourceFingerprint

    override fun readDatabaseVersions(context: RunContext): DatabaseVersions {
        enter(context)
        return try {
            val (versions, fingerprint) = query(
                "SELECT pg_catalog.current_setting('server_version'), e.extversion, " +
                    "pg_catalog.inet_server_addr()::pg_catalog.text, pg_catalog.inet_server_port(), " +
                    "pg_catalog.current_database(), SESSION_USER, CURRENT_USER " +
                    "FROM pg_catalog.pg_extension e WHERE e.extname OPERATOR(pg_catalog.=) 'hypopg'",
                context, ErrorCode.DB_CONNECTION_ERROR,
            ) { rows ->
                if (!rows.next()) fail(ErrorCode.DB_CONNECTION_ERROR, "HypoPG extension is not installed")
                val versions = DatabaseVersions(rows.getString(1), rows.getString(2))
                val address = rows.getString(3)
                val port = rows.getInt(4).takeUnless { rows.wasNull() }
                val database = rows.getString(5)
                val sessionUser = rows.getString(6)
                val currentUser = rows.getString(7)
                // A configured JDBC URL may list multiple hosts or a proxy. Only server-observed
                // facts identify the connection actually serving this session. Unix sockets have
                // no inet address/port, so their source identity remains explicitly unknown.
                val fingerprint = if (address.isNullOrBlank() || port == null || port <= 0 ||
                    database.isNullOrBlank() || sessionUser.isNullOrBlank() || currentUser.isNullOrBlank()) null
                else {
                    val identity = mapper.writeValueAsString(listOf(address, port, database, sessionUser, currentUser))
                    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(identity.toByteArray(Charsets.UTF_8)))
                }
                versions to fingerprint
            }
            // Publish cached facts only after the guarded query and its deadline check succeed.
            capturedVersions = versions
            capturedSourceFingerprint = fingerprint
            context.recordDatabaseVersions(versions)
            versions
        } catch (error: AnalysisException) {
            throw mapExistingError(error, context)
        }
    }

    private val closed = AtomicBoolean()
    private val operationLock = Any()
    private var activeOperation: Operation? = null
    private val watchdog = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "indexbudget-sql-deadline").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
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
                beforeExecute = { statement, run -> guardOperation(run, statement) },
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
            for (candidate in candidates.sortedBy { it.id }) {
                try {
                    createHypothesis(candidate, context)
                } catch (error: AnalysisException) {
                    if (error.error.code != ErrorCode.HYPOTHETICAL_INDEX_ERROR) throw error
                    throw AnalysisException(error.error.copy(
                        details = error.error.details + mapOf("candidate_id" to candidate.id),
                    )).also { attributed -> error.suppressed.forEach(attributed::addSuppressed) }
                }
            }
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
                } else {
                    try {
                        context.checkDeadline()
                    } catch (stop: AnalysisException) {
                        if ((primary as? AnalysisException)?.error?.code != stop.error.code) {
                            primary.addSuppressed(stop)
                        }
                    }
                }
            } else {
                try {
                    if (primary != null && !connection.autoCommit) {
                        guardOperation(context).use {
                            configureNetworkTimeout(context.remainingTime())
                            context.checkDeadline()
                            connection.rollback()
                        }
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
                guardOperation(context, statement).use {
                    ensureOpen(context)
                    val remaining = minOf(limit, context.remainingTime())
                    statement.queryTimeout = seconds(remaining)
                    statement.bind()
                    context.checkDeadline()
                    ensureOpen(context)
                    val rows = if (explain) context.withExplainAttempt { statement.executeQuery() }
                        else statement.executeQuery()
                    rows.use {
                        val result = read(rows)
                        context.checkDeadline()
                        result
                    }
                }
            }
        } catch (error: SQLException) {
            throw databaseError(error, code, context, queryId)
        }
    }

    /** Millisecond server timeout complements JDBC's rounded, whole-second timeout. */
    private fun configureTimeout(context: RunContext, limit: Duration) {
        try {
            ensureOpen(context)
            configureNetworkTimeout(context.remainingTime())
            connection.prepareStatement("SELECT pg_catalog.set_config('statement_timeout', ?, false)").use { statement ->
                guardOperation(context, statement).use {
                    ensureOpen(context)
                    statement.queryTimeout = seconds(context.remainingTime())
                    statement.setString(1, "${milliseconds(minOf(limit, context.remainingTime()))}ms")
                    context.checkDeadline()
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) fail(ErrorCode.DB_CONNECTION_ERROR, "Unable to set SQL timeout")
                    }
                    context.checkDeadline()
                }
            }
        } catch (error: SQLException) {
            // Failure of session configuration is never a local candidate/EXPLAIN failure.
            abortQuietly()
            throw databaseError(error, ErrorCode.DB_CONNECTION_ERROR, context)
        }
    }

    /** Each scope covers execute and result consumption, including catalog SQL and rollback. */
    private fun guardOperation(context: RunContext, statement: Statement? = null): AutoCloseable {
        val operation = Operation(context, statement)
        synchronized(operationLock) {
            ensureOpen(context)
            check(activeOperation == null) { "Concurrent SQL on one analysis session" }
            activeOperation = operation
            operation.arm()
        }
        return operation
    }

    private inner class Operation(val context: RunContext, val statement: Statement?) : AutoCloseable {
        private var timer: java.util.concurrent.ScheduledFuture<*>? = null

        fun arm() {
            timer = watchdog.schedule({
                synchronized(operationLock) {
                    if (activeOperation !== this || closed.get()) return@schedule
                    // Recheck the shared monotonic clock; test clocks may advance independently.
                    if (!context.isCancelled && !context.remainingTime().isZero) {
                        arm()
                        return@schedule
                    }
                }
                abortQuietly(this)
            }, context.remainingTime().toNanos(), TimeUnit.NANOSECONDS)
        }

        override fun close() {
            synchronized(operationLock) {
                timer?.cancel(false)
                if (activeOperation === this) activeOperation = null
            }
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
        val connectionFailure = jdbcConnectionFailure(error)
        val state = ((connectionFailure as? SQLException)?.sqlState ?: error.sqlState)
            ?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) }
        val broken = closed.get() || connectionFailure != null ||
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
        if (jdbcConnectionFailure(error) != null) abortQuietly()
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
        // Cancel is advisory; physical abort also releases a socket if cancel cannot reach the server.
        abortQuietly()
    }

    override fun close() {
        synchronized(operationLock) {
            if (!closed.compareAndSet(false, true)) return
        }
        watchdog.shutdownNow()
        try {
            connection.close()
        } catch (_: Exception) {
            try { connection.abort(DIRECT_EXECUTOR) } catch (_: Exception) { /* Best effort after failed close. */ }
            fail(ErrorCode.DB_CONNECTION_ERROR, "Unable to close analysis connection")
        }
    }

    private fun abortQuietly(expected: Operation? = null) {
        val statement = synchronized(operationLock) {
            if (expected != null && activeOperation !== expected) return
            if (!closed.compareAndSet(false, true)) return
            activeOperation?.statement
        }
        watchdog.shutdownNow()
        if (statement != null) {
            // A driver's cancel call may itself block on the network; it must not postpone abort.
            Thread.ofVirtual().name("indexbudget-sql-cancel").start {
                try { statement.cancel() } catch (_: Exception) { /* Physical abort is authoritative. */ }
            }
        }
        try {
            connection.abort(DIRECT_EXECUTOR)
        } catch (_: Exception) {
            try { connection.close() } catch (_: Exception) { /* The original failure remains primary. */ }
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
