package ru.itmo.indexbudget.postgres

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*

/** Fault injection only; backend isolation and transaction semantics are tested against PostgreSQL. */
class JdbcAnalysisSessionTest {
    @Test
    fun `root cost is used without summing children and complete plans are saved`() {
        val first = """[{"Plan":{"Total Cost":12.5,"Plans":[{"Total Cost":900}]}}]"""
        val second = """[{"Plan":{"Total Cost":0}}]"""
        val jdbc = FakeJdbc(listOf(first, second))
        val context = RunContext()
        JdbcAnalysisSession(jdbc.connection).use { session ->
            val result = session.evaluate(emptyList(), workload(2, reversed = true), context)
            assertEquals(linkedMapOf("q1" to 12.5, "q2" to 0.0), result.costByQueryId)
            assertEquals(mapOf("q1" to ExplainPlan(first), "q2" to ExplainPlan(second)), result.plansByQueryId)
            assertEquals(listOf("EXPLAIN (FORMAT JSON) SELECT 1", "EXPLAIN (FORMAT JSON) SELECT 2"), jdbc.explains())
            assertEquals(2, context.statistics().explainAttempts)
            assertEquals(1, context.statistics().evaluationAttempts)
            assertEquals(1, context.statistics().completedEvaluations)
            assertEquals(2, jdbc.resetAttempts)
            jdbc.assertResourcesReleased()
        }
        assertTrue(jdbc.closed)
    }

    @Test
    fun `negative overflowing nonnumeric and missing costs are explicit numeric errors`() {
        val plans = listOf(
            """[{"Plan":{"Total Cost":-0.01}}]""",
            """[{"Plan":{"Total Cost":1e400}}]""",
            """[{"Plan":{"Total Cost":1e-400}}]""",
            """[{"Plan":{"Total Cost":"12.5"}}]""",
            """[{"Plan":{"Total Cost":"NaN"}}]""",
            """[{"Plan":{"Total Cost":null}}]""",
            """[{"Plan":{}}]""",
        )
        for (plan in plans) {
            val jdbc = FakeJdbc(listOf(plan))
            val context = RunContext()
            JdbcAnalysisSession(jdbc.connection).use { session ->
                val error = assertThrows(AnalysisException::class.java) {
                    session.evaluate(emptyList(), workload(), context)
                }
                assertEquals(ErrorCode.NUMERIC_ERROR, error.error.code, plan)
                assertEquals("q1", error.error.queryId)
                assertEquals(1, context.statistics().explainAttempts)
                assertEquals(0, context.statistics().completedEvaluations)
                assertEquals(2, jdbc.resetAttempts)
                jdbc.assertResourcesReleased()
            }
        }
    }

    @Test
    fun `malformed plan JSON or shape does not become a zero cost`() {
        for (plan in listOf("not-json", "[]", "{}", "[{}]", """[{"Plan":null}]""")) {
            val jdbc = FakeJdbc(listOf(plan))
            JdbcAnalysisSession(jdbc.connection).use { session ->
                val error = assertThrows(AnalysisException::class.java) {
                    session.evaluate(emptyList(), workload(), RunContext())
                }
                assertEquals(ErrorCode.EXPLAIN_FAILED, error.error.code, plan)
                assertEquals("q1", error.error.queryId)
                assertNull(error.cause)
                jdbc.assertResourcesReleased()
            }
        }
    }

    @Test
    fun `a failed later SQL returns no incomplete evaluation and a later evaluation can succeed`() {
        val jdbc = FakeJdbc(listOf(plan(10), plan(20), plan(30)))
        jdbc.explainFailures[2] = SQLException(SECRET, "42601")
        val context = RunContext()
        JdbcAnalysisSession(jdbc.connection).use { session ->
            val error = assertThrows(AnalysisException::class.java) {
                session.evaluate(emptyList(), workload(2), context)
            }
            assertEquals(ErrorCode.EXPLAIN_FAILED, error.error.code)
            assertEquals("q2", error.error.queryId)
            assertEquals("42601", error.error.details["sql_state"])
            assertFalse(error.stackTraceToString().contains(SECRET))
            assertNull(error.cause)
            assertEquals(2, context.statistics().explainAttempts)
            assertEquals(1, context.statistics().evaluationAttempts)
            assertEquals(0, context.statistics().completedEvaluations)
            jdbc.assertResourcesReleased()

            val retry = session.evaluate(emptyList(), workload(2), context)
            assertEquals(mapOf("q1" to 20.0, "q2" to 30.0), retry.costByQueryId)
            assertEquals(4, context.statistics().explainAttempts)
            assertEquals(2, context.statistics().evaluationAttempts)
            assertEquals(1, context.statistics().completedEvaluations)
        }
    }

    @Test
    fun `cleanup failure preserves the original error and sanitizes both exceptions`() {
        val jdbc = FakeJdbc()
        jdbc.explainFailures[1] = SQLException(SECRET, "42601")
        jdbc.resetFailures[2] = SQLException("cleanup $SECRET", "XX000")
        JdbcAnalysisSession(jdbc.connection).use { session ->
            val error = assertThrows(AnalysisException::class.java) {
                session.evaluate(emptyList(), workload(), RunContext())
            }
            assertEquals(ErrorCode.EXPLAIN_FAILED, error.error.code)
            assertEquals("q1", error.error.queryId)
            assertEquals("42601", error.error.details["sql_state"])
            assertTrue(error.suppressed.isNotEmpty())
            assertTrue(error.suppressed.all { it is AnalysisException && it.cause == null })
            assertFalse(error.stackTraceToString().contains(SECRET))
            assertTrue(jdbc.closed)
        }
    }

    @Test
    fun `JDBC close failures suppressed by a plan error cannot leak driver secrets`() {
        val jdbc = FakeJdbc(listOf(null))
        jdbc.explainResultCloseFailure = SQLException("result $SECRET", "XX000")
        jdbc.explainStatementCloseFailure = SQLException("statement $SECRET", "XX000")
        JdbcAnalysisSession(jdbc.connection).use { session ->
            val error = assertThrows(AnalysisException::class.java) {
                session.evaluate(emptyList(), workload(), RunContext())
            }
            assertEquals(ErrorCode.EXPLAIN_FAILED, error.error.code)
            assertEquals("q1", error.error.queryId)
            assertTrue(error.suppressed.isNotEmpty())
            assertFalse(error.stackTraceToString().contains(SECRET))
            assertNull(error.cause)
            jdbc.assertResourcesReleased()
        }
    }

    @Test
    fun `disabled HypoPG fails explicitly without changing the setting or explaining queries`() {
        val jdbc = FakeJdbc()
        jdbc.hypopgEnabled = "off"
        val context = RunContext()
        JdbcAnalysisSession(jdbc.connection).use { session ->
            val error = assertThrows(AnalysisException::class.java) {
                session.evaluate(emptyList(), workload(), context)
            }
            assertEquals(ErrorCode.DB_CONNECTION_ERROR, error.error.code)
            assertEquals(0, context.statistics().explainAttempts)
            assertEquals(0, context.statistics().completedEvaluations)
            assertTrue(jdbc.explains().isEmpty())
            jdbc.assertResourcesReleased()
        }
    }

    @Test
    fun `broken connection is never a recoverable candidate or explain error`() {
        val jdbc = FakeJdbc()
        jdbc.explainFailures[1] = SQLException(SECRET, "08006")
        JdbcAnalysisSession(jdbc.connection).use { session ->
            val error = assertThrows(AnalysisException::class.java) {
                session.evaluate(emptyList(), workload(), RunContext())
            }
            assertEquals(ErrorCode.DB_CONNECTION_ERROR, error.error.code)
            assertTrue(jdbc.closed)
            assertEquals(1, jdbc.resetAttempts, "No cleanup SQL on a broken session")
            assertFalse(error.stackTraceToString().contains(SECRET))
        }
    }

    @Test
    fun `explain limit counts only executed statements and prevents partial completion`() {
        val jdbc = FakeJdbc(listOf(plan(10)))
        val context = RunContext(limits = RunLimits(maxExplainAttempts = 1))
        JdbcAnalysisSession(jdbc.connection).use { session ->
            val error = assertThrows(AnalysisException::class.java) {
                session.evaluate(emptyList(), workload(2), context)
            }
            assertEquals(ErrorCode.EXPLAIN_LIMIT, error.error.code)
            assertEquals(1, jdbc.explains().size)
            assertEquals(1, context.statistics().explainAttempts)
            assertEquals(0, context.statistics().completedEvaluations)
            jdbc.assertResourcesReleased()
        }
    }

    @Test
    fun `deadline during explain progress prevents SQL and closes connection without cleanup`() {
        var now = 0L
        var eventsAtDeadline = 0
        val jdbc = FakeJdbc(listOf(plan(10)))
        val context = RunContext(
            limits = RunLimits(commandTimeout = Duration.ofMillis(10)),
            clock = NanoClock { now },
            onProgress = { progress ->
                if (progress.statistics.explainAttempts == 1) {
                    eventsAtDeadline = jdbc.events.size
                    now = Duration.ofMillis(11).toNanos()
                }
            },
        )
        JdbcAnalysisSession(jdbc.connection).use { session ->
            val error = assertThrows(AnalysisException::class.java) {
                session.evaluate(emptyList(), workload(), context)
            }
            assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
            assertTrue(jdbc.explains().isEmpty())
            assertEquals(1, context.statistics().explainAttempts)
            assertEquals(0, context.statistics().completedEvaluations)
            assertEquals(1, jdbc.resetAttempts)
            assertTrue(jdbc.closed)
            assertEquals(listOf("CLOSE"), jdbc.events.drop(eventsAtDeadline))
            jdbc.assertResourcesReleased()
        }
    }

    @Test
    fun `deadline during explain closes connection without new cleanup SQL`() {
        var now = 0L
        val context = RunContext(
            limits = RunLimits(commandTimeout = Duration.ofMillis(10)),
            clock = NanoClock { now },
        )
        val jdbc = FakeJdbc(listOf(plan(10)))
        jdbc.afterExplain = { now = Duration.ofMillis(11).toNanos() }
        JdbcAnalysisSession(jdbc.connection).use { session ->
            val error = assertThrows(AnalysisException::class.java) {
                session.evaluate(emptyList(), workload(2), context)
            }
            assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
            assertEquals(1, jdbc.resetAttempts)
            assertEquals(1, jdbc.explains().size)
            assertEquals(0, context.statistics().completedEvaluations)
            assertTrue(jdbc.closed)
            assertTrue(jdbc.events.dropWhile { !it.startsWith("EXPLAIN") }.drop(1).none { it.startsWith("SELECT") })
            jdbc.assertResourcesReleased()
        }
    }

    @Test
    fun `size is raw bytes and does not consume explain or evaluation counters`() {
        val jdbc = FakeJdbc()
        jdbc.sizeBytes = 11L
        val context = RunContext()
        JdbcAnalysisSession(jdbc.connection).use { session ->
            assertEquals(11L, session.estimateSize(candidate(), context))
            assertEquals(RunStatistics(), context.statistics())
            assertEquals(2, jdbc.resetAttempts)
            jdbc.assertResourcesReleased()
        }
    }

    private fun workload(count: Int = 1, reversed: Boolean = false): ResolvedWorkload {
        val queries = (1..count).map { QueryInput("q$it", "SELECT $it") }.let { if (reversed) it.reversed() else it }
        val input = WorkloadInput(
            queries,
            listOf(Profile("profile", 1.0, queries.associate { it.id to 1.0 })),
            Constraints(1000L, null),
        )
        return ResolvedWorkload(input, queries.map {
            ResolvedQuery(it.id, it.sql, emptySet(), emptySet(), emptyList(), emptyList())
        })
    }

    private fun candidate(): IndexCandidate {
        val table = TableRef("public", "records")
        return IndexCandidate(
            "stable_candidate_id",
            IndexDefinition(table, listOf(IndexKey(ColumnRef(table, "id"), operatorClass = "pg_catalog.int4_ops"))),
            setOf("q1"), setOf(GenerationReason.SINGLE_COLUMN),
        )
    }

    private fun plan(cost: Int) = """[{"Plan":{"Total Cost":$cost}}]"""

    private class FakeJdbc(private val plans: List<String?> = emptyList()) {
        val events = mutableListOf<String>()
        val explainFailures = mutableMapOf<Int, SQLException>()
        val resetFailures = mutableMapOf<Int, SQLException>()
        var explainResultCloseFailure: SQLException? = null
        var explainStatementCloseFailure: SQLException? = null
        var afterExplain: () -> Unit = {}
        var resetAttempts = 0
        var sizeBytes = 11L
        var hypopgEnabled = "on"
        var closed = false
        private var explainAttempts = 0
        private var planIndex = 0
        private var openStatements = 0
        private var openResults = 0

        val connection: Connection = proxy(Connection::class.java) { method, args ->
            when (method) {
                "prepareStatement" -> statement(args[0] as String)
                "isClosed" -> closed
                "getAutoCommit" -> true
                "setNetworkTimeout" -> null
                "getNetworkTimeout" -> 0
                "rollback" -> { events += "ROLLBACK"; null }
                "abort", "close" -> { closed = true; events += "CLOSE"; null }
                else -> throw AssertionError("Unexpected Connection call: $method")
            }
        }

        fun explains(): List<String> = events.filter { it.startsWith("EXPLAIN") }

        fun assertResourcesReleased() {
            assertEquals(0, openStatements, "Unclosed JDBC statements")
            assertEquals(0, openResults, "Unclosed JDBC results")
        }

        private fun statement(sql: String): PreparedStatement {
            assertFalse(closed, "Prepared SQL on closed connection")
            openStatements++
            var statementClosed = false
            return proxy(PreparedStatement::class.java) { method, _ ->
                when (method) {
                    "setQueryTimeout", "setString", "setLong", "setObject" -> null
                    "executeQuery" -> {
                        events += sql
                        val value: Any? = when {
                            sql.contains("pg_catalog.pg_extension") -> "hypopg_schema"
                            sql.contains("set_config") -> "90000"
                            sql.contains("current_setting('hypopg.enabled')") -> hypopgEnabled
                            sql.contains("hypopg_reset") -> {
                                resetAttempts++
                                resetFailures[resetAttempts]?.let { throw it }
                                ""
                            }
                            sql.contains("hypopg_create_index") -> 1234L
                            sql.contains("hypopg_relation_size") -> sizeBytes
                            sql.startsWith("EXPLAIN") -> {
                                explainAttempts++
                                explainFailures[explainAttempts]?.let { throw it }
                                afterExplain()
                                plans[planIndex++]
                            }
                            else -> throw AssertionError("Unexpected SQL: $sql")
                        }
                        result(value, explainResultCloseFailure.takeIf { sql.startsWith("EXPLAIN") })
                    }
                    "close" -> {
                        if (!statementClosed) openStatements--
                        statementClosed = true
                        if (sql.startsWith("EXPLAIN")) explainStatementCloseFailure?.let { throw it }
                        null
                    }
                    "cancel" -> null
                    else -> throw AssertionError("Unexpected Statement call: $method")
                }
            }
        }

        private fun result(value: Any?, closeFailure: SQLException? = null): ResultSet {
            openResults++
            var beforeFirst = true
            var resultClosed = false
            return proxy(ResultSet::class.java) { method, _ ->
                when (method) {
                    "next" -> beforeFirst.also { beforeFirst = false }
                    "getString" -> value?.toString()
                    "getLong" -> (value as? Number)?.toLong() ?: 0L
                    "wasNull" -> value == null
                    "close" -> {
                        if (!resultClosed) openResults--
                        resultClosed = true
                        closeFailure?.let { throw it }
                        null
                    }
                    else -> throw AssertionError("Unexpected ResultSet call: $method")
                }
            }
        }

        private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>) -> Any?): T {
            val instance = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Fake${type.simpleName}"
                    else -> handler(method.name, args ?: emptyArray())
                }
            }
            return type.cast(instance)
        }
    }

    companion object {
        private const val SECRET = "jdbc:postgresql://secret-host/database?password=secret-password"
    }
}
