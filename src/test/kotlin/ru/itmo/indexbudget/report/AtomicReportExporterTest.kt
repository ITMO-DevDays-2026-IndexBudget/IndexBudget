package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tools.jackson.databind.json.JsonMapper

class AtomicReportExporterTest {
    @TempDir
    lateinit var directory: Path

    @ParameterizedTest
    @EnumSource(Objective::class)
    fun `all artifacts preserve one run and SQL contains only the selected definitions`(objective: Objective) {
        val report = ContractFixtures.report(objective = objective)
        val output = directory.resolve("run")
        var rendered: AnalysisReport? = null
        val exporter = AtomicReportExporter(renderer { rendered = it; html(it) })

        exporter.export(report, output, context(report))

        assertSame(report, rendered)
        assertEquals(setOf("report.json", "report.html", "indexes.sql"), names(output))
        val json = JsonMapper().readTree(Files.readString(output.resolve("report.json")))
        assertEquals(report.runId, json["run_id"].stringValue())
        assertEquals(report.inputHash, json["input_hash"].stringValue())
        assertEquals(objective.name.lowercase(), json["selected_objective"].stringValue())
        assertEquals(html(report), Files.readString(output.resolve("report.html")))
        val sql = Files.readString(output.resolve("indexes.sql"))
        assertTrue(sql.contains("-- run_id: ${report.runId}\n"))
        assertTrue(sql.contains("-- input_hash: ${report.inputHash}\n"))
        val selectedIds = checkNotNull(report.result.selected).evaluation.candidateIds
        for (candidate in report.candidates.candidates) {
            val ddl = PostgresDdlRenderer().render(candidate.definition)
            assertEquals(candidate.id in selectedIds, sql.contains(ddl))
        }
        assertEquals(setOf("run"), names(directory))
    }

    @Test
    fun `covering payload survives JSON HTML and SQL export with explicit report version`() {
        val fixture = ContractFixtures.report(sameIncumbents = true)
        val selectedId = checkNotNull(fixture.result.selected) { "Expected selected to be available" }.evaluation.candidateIds.single()
        val original = fixture.candidates.candidates.single { it.id == selectedId }
        val definition = original.definition.copy(includedColumns = listOf(ColumnRef(original.definition.table, "payload<\"x>")))
        val covering = original.copy(definition = definition, ddl = PostgresDdlRenderer().render(definition),
            generationReasons = original.generationReasons + GenerationReason.COVERING_PROJECTION)
        val calculator = MetricsCalculatorImpl()
        val input = requireNotNull(fixture.input)
        val assessment = calculator.assessBudget(listOf(covering), input)
        assertTrue(assessment.admissible)
        assertEquals(8200.0, assessment.usage.writeUnitsPerHour)
        val measured = covering.copy(writeUnitsPerHour = assessment.usage.writeUnitsPerHour,
            budgetedBytes = assessment.usage.budgetedBytes)
        val result = fixture.result as SearchResult.Completed
        val incumbent = result.bestRobust.copy(metrics = calculator.calculate(
            input, result.baseline, result.bestRobust.evaluation, assessment.usage))
        val report = fixture.copy(
            result = result.copy(bestRobust = incumbent, bestMean = incumbent),
            candidates = fixture.candidates.copy(candidates = listOf(measured)),
        )
        val output = directory.resolve("covering")
        AtomicReportExporter(HtmlReportRenderer()).export(report, output, context(report))
        val json = JsonMapper().readTree(Files.readString(output.resolve("report.json")))
        assertEquals(3, json["schema_version"].intValue())
        assertEquals("row_changes_times_key_and_include_columns", json["write_proxy_model"].stringValue())
        assertEquals(8200.0, json["selected_indexes"][0]["write_units_per_hour"].doubleValue())
        assertEquals(8200.0, json["candidates"][0]["write_units_per_hour"].doubleValue())
        for (configuration in listOf("selected", "best_robust", "best_mean")) {
            assertEquals(8200.0, json[configuration]["metrics"]["budget_usage"]["write_units_per_hour"].doubleValue())
        }
        assertEquals(0.0, json["baseline"]["metrics"]["budget_usage"]["write_units_per_hour"].doubleValue())
        assertEquals("payload<\"x>", json["selected_indexes"][0]["definition"]["included_columns"][0]["name"].stringValue())
        val html = Files.readString(output.resolve("report.html"))
        assertTrue(html.contains("INCLUDE"))
        assertTrue(html.contains("payload&lt;&quot;x&gt;"))
        assertTrue(html.contains("visibility map"))
        assertTrue(html.contains("<dt>Write units/час</dt><dd>8\u202f200</dd>"))
        assertFalse(html.contains("payload<\"x>"))
        assertTrue(Files.readString(output.resolve("indexes.sql")).contains(" INCLUDE (\"payload<\"\"x>\");"))
    }

    @Test
    fun `partial survives exhausted command budget and cancellation without losing saved plans`() {
        val report = ContractFixtures.report(status = RunStatus.PARTIAL)
        var elapsed = 0L
        val context = RunContext(report.runId, limits = RunLimits(commandTimeout = Duration.ofNanos(1)),
            clock = NanoClock { elapsed })
        context.recordInputHash(checkNotNull(report.inputHash))
        elapsed = 2L
        context.cancel()
        val output = directory.resolve("partial")

        AtomicReportExporter(renderer(::html)).export(report, output, context)

        assertEquals(setOf("report.json", "report.html", "indexes.sql"), names(output))
        val json = Files.readString(output.resolve("report.json"))
        assertTrue(json.contains("partial"))
        assertTrue(json.contains("Fixture Scan"))
        assertEquals(RunStatistics(), context.statistics())
    }

    @Test
    fun `failed output contains diagnostics but no successful SQL even without an input hash`() {
        val report = ContractFixtures.report(status = RunStatus.FAILED).copy(inputHash = null)
        val output = directory.resolve("failed")

        AtomicReportExporter(renderer(::html)).export(report, output, context(report))

        assertEquals(setOf("report.json", "report.html"), names(output))
        val json = JsonMapper().readTree(Files.readString(output.resolve("report.json")))
        assertEquals("failed", json["status"].stringValue())
        assertTrue(json["input_hash"].isNull)
    }

    @Test
    fun `empty selected set and identical incumbents produce an empty recommendation script`() {
        val empty = ContractFixtures.baselineConfiguration()
        val original = ContractFixtures.report()
        val report = original.copy(result = SearchResult.Completed(Objective.ROBUST,
            empty.evaluation, empty, empty), candidates = CandidateBatch(emptyList()), priceOfRobustness = 0.0)
        val output = directory.resolve("empty")
        Files.createDirectory(output)

        AtomicReportExporter(renderer(::html)).export(report, output, context(report))

        assertFalse(Files.readString(output.resolve("indexes.sql")).contains("CREATE INDEX"))
        assertTrue(Files.readString(output.resolve("indexes.sql")).contains(report.runId))
        assertEquals(setOf("empty"), names(directory))
    }

    @Test
    fun `old artifacts are preserved and never mixed with a new report`() {
        val output = Files.createDirectory(directory.resolve("existing"))
        Files.writeString(output.resolve("indexes.sql"), "old recommendations")
        val report = ContractFixtures.report()

        assertExportFailed { AtomicReportExporter(renderer(::html)).export(report, output, context(report)) }

        assertEquals(setOf("indexes.sql"), names(output))
        assertEquals("old recommendations", Files.readString(output.resolve("indexes.sql")))
        assertEquals(setOf("existing"), names(directory))
    }

    @Test
    fun `a destination occupied while rendering is preserved and staged files are removed`() {
        val output = directory.resolve("occupied-later")
        val report = ContractFixtures.report()
        val exporter = AtomicReportExporter(renderer {
            Files.createDirectory(output)
            Files.writeString(output.resolve("other.txt"), "unrelated data")
            html(it)
        })

        assertExportFailed { exporter.export(report, output, context(report)) }

        assertEquals(setOf("other.txt"), names(output))
        assertEquals("unrelated data", Files.readString(output.resolve("other.txt")))
        assertEquals(setOf("occupied-later"), names(directory))
    }

    @Test
    fun `renderer failures expose no exception secrets or partial artifacts`() {
        val output = directory.resolve("renderer-error")
        val report = ContractFixtures.report()
        val exporter = AtomicReportExporter(renderer {
            error("jdbc:postgresql://secret-host/db?password=hidden-password")
        })

        val error = assertExportFailed { exporter.export(report, output, context(report)) }

        assertFalse(error.toString().contains("secret-host"))
        assertFalse(error.toString().contains("hidden-password"))
        assertNull(error.cause)
        assertFalse(Files.exists(output))
        assertTrue(names(directory).isEmpty())
    }

    @Test
    fun `invalid selected definitions cannot publish already rendered JSON or HTML`() {
        val original = ContractFixtures.report()
        val report = original.copy(candidates = original.candidates.copy(candidates =
            original.candidates.candidates.map { candidate -> candidate.copy(definition =
                candidate.definition.copy(keys = candidate.definition.keys.map { it.copy(operatorClass = null) })) },
        ))
        val output = directory.resolve("bad-ddl")

        assertExportFailed { AtomicReportExporter(renderer(::html)).export(report, output, context(report)) }

        assertFalse(Files.exists(output))
        assertTrue(names(directory).isEmpty())
    }

    @Test
    fun `stale selected DDL cannot differ between report and SQL artifacts`() {
        val original = ContractFixtures.report()
        val report = original.copy(candidates = original.candidates.copy(
            candidates = original.candidates.candidates.map { it.copy(ddl = "DROP TABLE malicious;") },
        ))
        val output = directory.resolve("stale-ddl")

        assertExportFailed { AtomicReportExporter(renderer(::html)).export(report, output, context(report)) }

        assertFalse(Files.exists(output))
        assertTrue(names(directory).isEmpty())
    }

    @Test
    fun `write failure is reported without replacing a foreign file`() {
        val parentFile = directory.resolve("parent-file")
        Files.writeString(parentFile, "keep")
        val report = ContractFixtures.report()

        assertExportFailed {
            AtomicReportExporter(renderer(::html)).export(report, parentFile.resolve("run"), context(report))
        }

        assertEquals("keep", Files.readString(parentFile))
        assertEquals(setOf("parent-file"), names(directory))
    }

    @Test
    fun `a filesystem write failure after JSON staging publishes no artifact`() {
        val report = ContractFixtures.report()
        val output = directory.resolve("mid-write-failure")
        val exporter = AtomicReportExporter(renderer {
            val staging = Files.list(directory).use { files ->
                files.filter { it.fileName.toString().startsWith(".indexbudget-${report.runId}-") }
                    .findFirst().orElseThrow()
            }
            assertTrue(Files.isRegularFile(staging.resolve("report.json")))
            // CREATE_NEW must fail even if another filesystem operation claimed the next name.
            Files.createDirectory(staging.resolve("report.html"))
            html(it)
        })

        assertExportFailed { exporter.export(report, output, context(report)) }

        assertFalse(Files.exists(output))
        assertTrue(names(directory).isEmpty())
    }

    @Test
    fun `two competing exports publish exactly one complete run`() {
        val output = Files.createDirectory(directory.resolve("concurrent"))
        val barrier = CyclicBarrier(2)
        val reports = listOf("first-run", "second-run").map { ContractFixtures.report().copy(runId = it) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(reports.map { report -> Callable {
                val exporter = AtomicReportExporter(renderer { barrier.await(10, TimeUnit.SECONDS); html(it) })
                try {
                    exporter.export(report, output, context(report))
                    report.runId
                } catch (error: AnalysisException) {
                    assertEquals(ErrorCode.EXPORT_FAILED, error.error.code)
                    null
                }
            } }).map { it.get(15, TimeUnit.SECONDS) }
            val winner = results.filterNotNull().single()
            val json = JsonMapper().readTree(Files.readString(output.resolve("report.json")))
            assertEquals(winner, json["run_id"].stringValue())
            assertTrue(Files.readString(output.resolve("report.html")).contains(winner))
            assertTrue(Files.readString(output.resolve("indexes.sql")).contains("-- run_id: $winner\n"))
            assertEquals(setOf("report.json", "report.html", "indexes.sql"), names(output))
            assertEquals(setOf("concurrent"), names(directory))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an output symlink is preserved and its empty target receives the full run`() {
        val target = Files.createDirectory(directory.resolve("target"))
        val link = Files.createSymbolicLink(directory.resolve("link"), target)
        val report = ContractFixtures.report()

        AtomicReportExporter(renderer(::html)).export(report, link, context(report))

        assertTrue(Files.isSymbolicLink(link))
        assertEquals(setOf("report.json", "report.html", "indexes.sql"), names(target))
        assertEquals(Files.readString(target.resolve("report.json")), Files.readString(link.resolve("report.json")))
        assertEquals(setOf("target", "link"), names(directory))
    }

    @Test
    fun `mismatched identities missing and duplicate candidates are rejected before publication`() {
        val report = ContractFixtures.report()
        val badReports = listOf(
            report.copy(runId = "different-run"),
            report.copy(inputHash = "0".repeat(64)),
            report.copy(candidates = CandidateBatch(emptyList())),
            report.copy(candidates = CandidateBatch(report.candidates.candidates + report.candidates.candidates.first())),
        )
        for ((index, invalid) in badReports.withIndex()) {
            assertExportFailed {
                AtomicReportExporter(renderer(::html)).export(invalid, directory.resolve("invalid-$index"), context(report))
            }
        }
        assertTrue(names(directory).isEmpty())
    }

    @Test
    fun `run identifiers cannot inject paths or SQL comment lines`() {
        for (runId in listOf("../outside", "run\nCREATE INDEX injected;")) {
            val report = ContractFixtures.report().copy(runId = runId)
            assertExportFailed {
                AtomicReportExporter(renderer(::html)).export(report, directory.resolve("invalid"), context(report))
            }
        }
        assertTrue(names(directory).isEmpty())
    }

    private fun context(report: AnalysisReport): RunContext = RunContext(report.runId).also { context ->
        report.inputHash?.let(context::recordInputHash)
    }

    private fun html(report: AnalysisReport): String =
        "<html><body>${report.runId} ${report.inputHash} ${report.result.selected?.evaluation?.candidateIds}</body></html>"

    private fun renderer(render: (AnalysisReport) -> String): ReportRenderer = object : ReportRenderer {
        override fun render(report: AnalysisReport): String = render.invoke(report)
    }

    private fun names(path: Path): Set<String> = Files.list(path).use { files ->
        files.map { it.fileName.toString() }.toList().toSet()
    }

    private fun assertExportFailed(action: () -> Unit): AnalysisException =
        assertThrows(AnalysisException::class.java, action).also { assertEquals(ErrorCode.EXPORT_FAILED, it.error.code) }
}
