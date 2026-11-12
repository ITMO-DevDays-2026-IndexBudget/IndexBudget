package ru.itmo.indexbudget.statistics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.itmo.indexbudget.domain.*
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Runs only against the disposable PostgreSQL started by demo/check-statistics.sh. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_STATS_TEST_URL", matches = ".+")
class StatisticsIntegrationTest {
    private val url get() = System.getenv("INDEXBUDGET_STATS_TEST_URL")
    private val source = UUID.fromString("d0d60c19-eb9d-42ee-9e01-935560eb7e20")

    @BeforeEach fun setup() {
        connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
            s.execute("CREATE TABLE IF NOT EXISTS public.snapshot_probe (value integer)")
            s.execute("DO 'BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = ''stats_reader'') THEN CREATE ROLE stats_reader LOGIN; END IF; END'")
            s.execute("GRANT SELECT ON public.snapshot_probe TO stats_reader")
        } }
    }

    @Test fun `known workload increments counters and all collection connections close`() {
        val opened = mutableListOf<Connection>()
        val collector = JdbcStatisticsCollector({ connection().also { opened += it } })
        val before = collector.collect(source, SnapshotLimits(), RunContext())
        connection().use { c -> c.createStatement().use { s ->
            repeat(7) { s.executeQuery("SELECT count(*) FROM public.snapshot_probe WHERE value >= 17").use { it.next() } }
        } }
        val after = collector.collect(source, SnapshotLimits(), RunContext())
        assertTrue(before.complete)
        assertTrue(after.complete)
        val observed = after.entries.single { it.query?.contains("count(*) FROM public.snapshot_probe") == true }
        val old = before.entries.singleOrNull {
            it.dbid == observed.dbid && it.userid == observed.userid && it.queryid == observed.queryid && it.toplevel == observed.toplevel
        }
        assertEquals(7L, observed.calls - (old?.calls ?: 0L))
        assertTrue(observed.totalExecTime >= (old?.totalExecTime ?: 0.0))
        assertEquals(before.before, after.after)
        assertTrue(opened.all { it.isClosed })
    }

    @Test fun `restricted role retains foreign counters but does not reveal text or identity`() {
        connection().use { c -> c.createStatement().use { it.executeQuery("SELECT count(*) FROM public.snapshot_probe").close() } }
        val result = JdbcStatisticsCollector({ connection("stats_reader") }).collect(source, SnapshotLimits(), RunContext())
        assertFalse(result.source.allQueryTextsVisible)
        val hidden = result.entries.filter { it.userid != result.source.readerUserid }
        assertTrue(hidden.isNotEmpty())
        assertTrue(hidden.all { it.queryid == null && it.query == null && it.textStatus == QueryTextStatus.RESTRICTED })
    }

    @Test fun `frequency calculator consumes actual collector snapshots without inventing an initial counter`() {
        fun executeProbe(times: Int) = connection().use { connection -> connection.createStatement().use { statement ->
            repeat(times) { statement.executeQuery("SELECT value FROM public.snapshot_probe WHERE value = 23").close() }
        } }
        executeProbe(1)
        val collector = JdbcStatisticsCollector({ connection() })
        val before = collector.collect(source, SnapshotLimits(), RunContext())
        executeProbe(7)
        val after = collector.collect(source, SnapshotLimits(), RunContext())
        val profile = FrequencyCalculator().calculate(listOf(FrequencyWindow("observed", 1.0, before, after))).profiles.single()
        val row = profile.observations.single { it.query?.startsWith("SELECT value FROM public.snapshot_probe") == true }
        assertEquals(ObservationState.OBSERVED, row.state)
        assertEquals(7L, row.deltaCalls)
        val expected = 7.0 * 3600.0 / profile.period.durationSeconds
        assertEquals(expected, requireNotNull(row.callsPerHour), expected * 1e-12)
        assertTrue(requireNotNull(row.totalExecTimeMillis) >= 0.0)
        assertEquals(0L, row.deltaRows)
    }

    @Test fun `restricted entries stay restricted with a one byte text cap`() {
        connection().use { c -> c.createStatement().use { it.executeQuery("SELECT count(*) FROM public.snapshot_probe").close() } }
        val result = JdbcStatisticsCollector({ connection("stats_reader") }).collect(source,
            SnapshotLimits(maxQueryBytes = 1), RunContext())
        val hidden = result.entries.filter { it.userid != result.source.readerUserid }
        assertTrue(hidden.isNotEmpty())
        assertTrue(hidden.all { it.queryid == null && it.query == null && it.textStatus == QueryTextStatus.RESTRICTED })
        // Own visible queries may legitimately exceed one byte; no assertion hides their truncation.
    }

    @Test fun `missing extension produces explicit error and releases connection`() {
        var opened: Connection? = null
        val missing = JdbcStatisticsCollector({ DriverManager.getConnection(url.replace("/stats_test", "/postgres"), "postgres", "").also { opened = it } })
        val error = assertThrows(AnalysisException::class.java) { missing.collect(source, SnapshotLimits(), RunContext()) }
        assertEquals(ErrorCode.STATISTICS_UNAVAILABLE, error.error.code)
        assertTrue(checkNotNull(opened) { "Expected opened to be available" }.isClosed)
    }

    @Test fun `real cursor stops at explicit row limit without complete status`() {
        connection().use { c -> c.createStatement().use { it.executeQuery("SELECT count(*) FROM public.snapshot_probe").close() } }
        val result = JdbcStatisticsCollector({ connection() }).collect(source, SnapshotLimits(maxRows = 1), RunContext())
        assertEquals(1, result.entries.size)
        assertTrue(SnapshotReason.ROW_LIMIT in result.reasons)
    }

    private fun connection(user: String = "postgres") = DriverManager.getConnection(url, user, "")
}
