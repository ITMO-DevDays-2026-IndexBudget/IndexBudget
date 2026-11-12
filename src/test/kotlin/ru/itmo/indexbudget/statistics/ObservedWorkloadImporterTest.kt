package ru.itmo.indexbudget.statistics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.cli.ImportWorkloadCommand
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.fixtures.withSqlSemantics
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.HtmlReportRenderer
import ru.itmo.indexbudget.report.JsonReportWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class ObservedWorkloadImporterTest {
    @TempDir lateinit var directory: Path
    private val source = StatisticsSource(UUID.fromString("d0d60c19-eb9d-42ee-9e01-935560eb7e20"), 42, 10, true, "16.15", "1.10", "top")
    private val epoch = StatisticsEpoch("2026-11-10T00:00:00Z", 0)
    private val template = "SELECT * FROM public.records WHERE group_id = $1"
    private val exampleSql = "SELECT * FROM public.records WHERE group_id = 1"
    private val snapshot = ContractFixtures.catalog().withSqlSemantics()
    private val loader = ImportCatalog { _, path, _ -> snapshot.copy(searchPath = path) }
    private val options: Map<String, Any?> = mapOf("constraints" to mapOf("storage_budget_bytes" to 1048576,
        "write_budget_units_per_hour" to null), "objective" to "robust")

    @Test fun `restricted DML imports through selection frequencies and full semantic validation`() {
        for ((template, example, kind) in listOf(
            Triple("UPDATE public.records SET value=$1 WHERE group_id=$2",
                "UPDATE public.records SET value=7 WHERE group_id=1", StatementKind.UPDATE),
            Triple("DELETE FROM public.records WHERE group_id=$1",
                "DELETE FROM public.records WHERE group_id=1", StatementKind.DELETE),
        )) {
            val result = build(frequencies(template), listOf(SqlExample(key(), example, listOf("pg_catalog", "public"))))
            assertNull(result.error)
            assertEquals(kind, result.resolvedQueries.single().statementKind)
            assertEquals(3600.0, read(result).profiles.single().callsPerHour.values.single())
            assertTrue(read(result).queries.single().sql.startsWith(kind.name))
        }
    }

    @Test fun `template catalog includes its own unqualified table before comparing physical schemas`() {
        val archive = snapshot.tables.single().copy(ref = TableRef("archive", "records"))
        val available = snapshot.tables + archive
        val importer = ObservedWorkloadImporter(ImportCatalog { names, path, _ ->
            assertTrue(TableName("records") in names)
            snapshot.copy(searchPath = path, tables = names.mapNotNull { name ->
                if (name.schema != null) available.firstOrNull { it.ref == TableRef(name.schema, name.name) }
                else path.firstNotNullOfOrNull { schema -> available.firstOrNull { it.ref == TableRef(schema, name.name) } }
            }.distinct())
        })
        val input = frequencies("SELECT * FROM records WHERE group_id=$1")
        val example = SqlExample(key(), "SELECT * FROM archive.records WHERE group_id=1", listOf("pg_catalog", "public", "archive"))
        assertNotNull(importer.build(input, listOf(example), options).error)
        assertNull(importer.build(input, listOf(example.copy(sql = exampleSql)), options).error)
    }

    @Test fun `quoted dotted projection survives AST matching and exported SQL`() {
        val table = TableRef("public", "records")
        val catalog = snapshot.copy(tables = snapshot.tables.map { it.copy(columns = it.columns + listOf(
            CatalogColumn("a.b", "int4", defaultOperatorClass = "pg_catalog.int4_ops"),
            CatalogColumn("b", "int4", defaultOperatorClass = "pg_catalog.int4_ops"))) }).withSqlSemantics()
        val importer = ObservedWorkloadImporter(ImportCatalog { _, path, _ -> catalog.copy(searchPath = path) })
        val result = importer.build(frequencies("SELECT \"a.b\" FROM public.records a WHERE group_id=$1"),
            listOf(SqlExample(key(), "SELECT \"a.b\" FROM public.records a WHERE group_id=1", listOf("pg_catalog", "public"))), options)
        assertNull(result.error)
        assertTrue(read(result).queries.single().sql.startsWith("SELECT \"a.b\" FROM"))
        assertEquals(setOf(ColumnRef(table, "a.b")), result.resolvedQueries.single().projectedColumns)
    }

    @Test fun `snapshot query byte limit accepts exact UTF8 boundary and rejects one byte above`() {
        for (query in listOf("SELECT 1", "SELECT 'я'")) {
            val size = query.toByteArray(Charsets.UTF_8).size
            val value = capture(0, listOf(row(100, query))).copy(limits = SnapshotLimits(maxQueryBytes = size))
            val file = directory.resolve("limit.json")
            Files.write(file, SnapshotJson().render(value))
            assertEquals(query, ImportJson.snapshot(file, RunContext()).entries.single().query)
            Files.write(file, SnapshotJson().render(value.copy(limits = value.limits.copy(maxQueryBytes = size - 1))))
            assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, assertThrows(AnalysisException::class.java) {
                ImportJson.snapshot(file, RunContext())
            }.error.code)
        }
    }

    @Test fun `CLI missing example key and snapshot before are input errors before opening database`() {
        for (missing in listOf("key", "before")) {
            val before = tree(ImportJson.mapper.readTree(SnapshotJson().render(capture(0, listOf(row(100))))))
            Files.write(directory.resolve("before.json"), ImportJson.mapper.writeValueAsBytes(before.properties()
                .filter { missing != "before" || it.key != "before" }.associate { it.key to it.value }))
            Files.write(directory.resolve("after.json"), SnapshotJson().render(capture(60, listOf(row(160)))))
            val example = mutableMapOf<String, Any>("sql" to exampleSql, "search_path" to listOf("pg_catalog", "public"))
            if (missing != "key") example["key"] = ImportJson.key(key())
            Files.write(directory.resolve("examples.json"), ImportJson.mapper.writeValueAsBytes(mapOf("schema_version" to 1,
                "kind" to "pg_stat_statements_examples", "examples" to listOf(example))))
            Files.write(directory.resolve("config.json"), ImportJson.mapper.writeValueAsBytes(mapOf("schema_version" to 1,
                "kind" to "pg_stat_statements_import_config", "examples" to "examples.json", "windows" to listOf(
                    mapOf("id" to "day", "weight" to 1, "before" to "before.json", "after" to "after.json"))) + options))
            val output = directory.resolve("output-$missing")
            val err = StringWriter()
            val exit = ImportWorkloadCommand { fail("Database must not be opened") }.execute(arrayOf("import-workload",
                "--config", directory.resolve("config.json").toString(), "--output-dir", output.toString()),
                PrintWriter(StringWriter(), true), PrintWriter(err, true))
            assertEquals(2, exit, err.toString())
            assertTrue(err.toString().startsWith("INVALID_INPUT:"))
            assertFalse(Files.exists(output))
        }
    }

    @Test fun `snapshot wire format round trips bigint identities and frequency windows`() {
        val before = capture(0, listOf(row(100).copy(queryid = Long.MIN_VALUE)))
        val path = directory.resolve("snapshot.json")
        Files.write(path, SnapshotJson().render(before))
        assertEquals(before, ImportJson.snapshot(path, RunContext()))
    }

    @Test fun `versioned repository example passes snapshot frequency importer and workload validation`() {
        val folder = Path.of("examples/observed-import")
        val context = RunContext()
        val config = ImportJson.read(folder.resolve("import-config.json"), 1024 * 1024, context)
        val nodes = ImportJson.read(folder.resolve(ImportJson.text(config, "examples")), 1024 * 1024, context)
        val examples = ImportJson.array(nodes, "examples").map { node -> SqlExample(ImportJson.key(node["key"]),
            ImportJson.text(node, "sql"), ImportJson.array(node, "search_path").map { it.asString() }) }
        val window = ImportJson.array(config, "windows").single()
        val input = FrequencyCalculator().calculate(listOf(FrequencyWindow(ImportJson.text(window, "id"),
            ImportJson.number(window, "weight"), ImportJson.snapshot(folder.resolve(ImportJson.text(window, "before")), context),
            ImportJson.snapshot(folder.resolve(ImportJson.text(window, "after")), context))))
        val result = ObservedWorkloadImporter(loader).build(input, examples, mapOf("constraints" to config["constraints"]))
        assertNull(result.error)
        assertEquals(3600.0, read(result).profiles.single().callsPerHour.values.single())
        assertEquals(90.0, tree(result.manifest)["periods"][0]["available_historical_exec_time_ms"].doubleValue())
    }

    @Test fun `all exclusions and an empty profile remain inspectable in a failed manifest`() {
        val result = ObservedWorkloadImporter(loader).build(frequencies(), emptyList(), options)
        assertEquals(ErrorCode.INVALID_INPUT, result.error?.code)
        assertEquals("failed", tree(result.manifest)["status"].asString())
        assertEquals("MISSING_EXAMPLE", tree(result.manifest)["entries"][0]["reason"].asString())
        assertTrue(result.resolvedQueries.isEmpty())
    }

    @Test fun `literal example produces replayable workload and linked manifest without historical costs`() {
        val input = frequencies()
        val result = build(input)
        val node = tree(result.workload)
        assertEquals(2, node["schema_version"].intValue())
        assertEquals(3600.0, node["profiles"][0]["calls_per_hour"][ObservedWorkloadImporter.id(key())].doubleValue())
        assertTrue(node["queries"][0]["sql"].asString().contains("\"public\".records"))
        val provenance = node["provenance"]
        assertTrue(provenance["selection_rule"].asString().endsWith(ObservedWorkloadImporter.hash(ImportJson.mapper.writeValueAsBytes(result.manifest))))
        assertEquals(60L, provenance["windows"][0]["coverage"]["selected_calls"].longValue())
        val workload = read(result)
        assertNotNull(workload.provenance)
        assertEquals(1, workload.queries.size)
        assertEquals(ObservedWorkloadImporter.id(key()), workload.queries.single().id)
        val baseline = EvaluationResult(emptyList(), workload.queries.associate { it.id to 10.0 },
            workload.queries.associate { it.id to ExplainPlan("{}") })
        val metrics = MetricsCalculatorImpl().calculate(workload, baseline, baseline, BudgetUsage(0, 0, null, 0))
        val empty = EvaluatedConfiguration(baseline, metrics)
        val report = DefaultReportBuilder().build(workload, CandidateBatch(emptyList()),
            SearchResult.Completed(workload.objective, baseline, empty, empty), RunContext())
        val json = ImportJson.mapper.readTree(JsonReportWriter().render(report))
        assertEquals(provenance, json["workload_provenance"])
        assertEquals(10.0, json["baseline"]["cost_by_query_id"][workload.queries.single().id].doubleValue())
        val provenanceHtml = HtmlReportRenderer().render(report).substringAfter("<section id=\"provenance\"").substringBefore("</section>")
        assertTrue(provenanceHtml.contains("Исходные запросы и частоты"))
        assertTrue(provenanceHtml.contains("import-manifest.json"))
        assertTrue(provenanceHtml.contains("pg_stat_statements"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["SELECT * FROM public.records WHERE value = 1", "SELECT * FROM public.records WHERE group_id > 1",
        "SELECT * FROM public.records WHERE group_id = 1 OR value = 2", "SELECT id FROM public.records WHERE group_id = 1",
        "SELECT * FROM public.records WHERE group_id = $1", "DELETE FROM public.records WHERE group_id=1"])
    fun `different AST or remaining parameters cannot inherit template statistics`(example: String) {
        assertThrows(AnalysisException::class.java) { build(frequencies(), listOf(SqlExample(key(), example, listOf("pg_catalog", "public")))) }
    }

    @Test fun `repeated numbered parameters require consistent representative values`() {
        val matcher = SqlTemplateMatcher(RunContext())
        val original = "SELECT * FROM public.records WHERE group_id=$1 OR group_id=$1"
        assertTrue(matcher.match(original, "SELECT * FROM public.records WHERE group_id=1 OR group_id=1", snapshot))
        assertFalse(matcher.match(original, "SELECT * FROM public.records WHERE group_id=1 OR group_id=2", snapshot))
    }

    @Test fun `casts and scalar IN BETWEEN NULL preserve shape and reject changed types or arity`() {
        val matcher = SqlTemplateMatcher(RunContext())
        assertTrue(matcher.match("SELECT * FROM public.records WHERE group_id=CAST($1 AS integer)",
            "SELECT * FROM public.records WHERE group_id=CAST(1 AS integer)", snapshot))
        assertFalse(matcher.match("SELECT * FROM public.records WHERE group_id=CAST($1 AS integer)",
            "SELECT * FROM public.records WHERE group_id=CAST(1 AS bigint)", snapshot))
        assertTrue(matcher.match("SELECT * FROM public.records WHERE group_id IN ($1, $2) OR value BETWEEN $3 AND $4",
            "SELECT * FROM public.records WHERE group_id IN (1, NULL) OR value BETWEEN 2 AND 3", snapshot))
        assertFalse(matcher.match("SELECT * FROM public.records WHERE group_id IN ($1, $2)",
            "SELECT * FROM public.records WHERE group_id IN (1)", snapshot))
    }

    @Test fun `type errors and ambiguous columns are rejected by the existing resolver`() {
        val first = SqlExample(key(), "SELECT * FROM public.records WHERE group_id=CAST(1 AS timestamp)", listOf("pg_catalog", "public"))
        assertThrows(AnalysisException::class.java) { build(frequencies(), listOf(first)) }
        val sql = "SELECT group_id FROM public.records r INNER JOIN public.records s ON r.id=s.id WHERE group_id=1"
        assertThrows(AnalysisException::class.java) { build(frequencies(), listOf(first.copy(sql = sql))) }
    }

    @Test fun `explicit context resolves schemas and freezes physical tables instead of guessing a path`() {
        val raw = frequencies(query = "SELECT * FROM records WHERE group_id=$1")
        val good = SqlExample(key(), "SELECT * FROM records WHERE group_id=1", listOf("pg_catalog", "public"))
        assertTrue(tree(build(raw, listOf(good)).workload)["queries"][0]["sql"].asString().contains("\"public\".records"))
        assertThrows(AnalysisException::class.java) { build(raw, listOf(good.copy(searchPath = emptyList()))) }
        assertThrows(AnalysisException::class.java) { build(raw, listOf(good.copy(searchPath = listOf("pg_catalog", "\$user")))) }
    }

    @Test fun `full identity distinguishes same queryid under different roles but nested calls are excluded`() {
        val before = listOf(row(100), row(100).copy(userid = 11), row(100).copy(toplevel = false))
        val input = FrequencyCalculator().calculate(listOf(FrequencyWindow("day", 1.0,
            capture(0, before), capture(60, before.map { it.copy(calls = 160) }))))
        val result = build(input, listOf(SqlExample(key(), exampleSql, listOf("pg_catalog", "public")),
            SqlExample(key().copy(userid = 11), exampleSql, listOf("pg_catalog", "public"))))
        assertEquals(2, tree(result.workload)["queries"].size())
        assertEquals(2, read(result).queries.map { it.id }.distinct().size)
        assertEquals("NESTED_STATEMENT", tree(result.manifest)["entries"].single { !it["key"]["toplevel"].booleanValue() }["reason"].asString())
        assertEquals(7200.0, tree(result.manifest)["periods"][0]["available_calls_per_hour"].doubleValue())
    }

    @Test fun `unknown windows never become zero and all excluded identities keep reasons`() {
        val before = listOf(row(100), row(100).copy(queryid = 2), row(100).copy(queryid = 3, query = "DELETE FROM public.records"))
        val after = listOf(row(160), row(160).copy(queryid = 3, query = "DELETE FROM public.records"), row(1).copy(queryid = 4))
        val input = FrequencyCalculator().calculate(listOf(FrequencyWindow("day", 1.0, capture(0, before), capture(60, after))))
        val result = build(input)
        val reasons = tree(result.manifest)["entries"].associate { it["key"]["queryid"].asString() to it["reason"] }
        assertEquals("INCOMPLETE_OBSERVATION", reasons.getValue("2").asString())
        assertEquals("UNSUPPORTED_STATEMENT", reasons.getValue("3").asString())
        assertEquals("INCOMPLETE_OBSERVATION", reasons.getValue("4").asString())
        assertEquals(0.5, tree(result.manifest)["periods"][0]["calls_coverage"].doubleValue())
        assertEquals(1, read(result).queries.size)
    }

    @Test fun `confirmed zero remains zero in a nonempty profile but unknown in any profile excludes the key`() {
        val before = listOf(row(100), row(100).copy(queryid = 2))
        val day = FrequencyWindow("day", 1.0, capture(0, before), capture(60, before.map { it.copy(calls = 160) }))
        val night = FrequencyWindow("night", 1.0, capture(120, before), capture(180, listOf(row(100), row(160).copy(queryid = 2))))
        val examples = (1L..2L).map { SqlExample(key().copy(queryid = it), exampleSql, listOf("pg_catalog", "public")) }
        val input = FrequencyCalculator().calculate(listOf(day, night))
        assertEquals(0.0, read(build(input, examples)).profiles.last().callsPerHour.getValue(ObservedWorkloadImporter.id(key())))
        val unknown = night.copy(after = night.after.copy(entries = listOf(row(160).copy(queryid = 2))))
        val result = read(build(FrequencyCalculator().calculate(listOf(day, unknown)), examples))
        assertEquals(listOf(ObservedWorkloadImporter.id(key().copy(queryid = 2))), result.queries.map { it.id })
    }

    @Test fun `over twenty queries preserve rare profile opportunity deterministic output and exact exclusions`() {
        val before = (1L..25L).map { row(100).copy(queryid = it) }
        val day = FrequencyWindow("day", 100.0, capture(0, before), capture(60, before.mapIndexed { i, row -> row.copy(calls = 200 - i.toLong()) }))
        val night = FrequencyWindow("night", 1.0, capture(120, before), capture(180, before.mapIndexed { i, row -> row.copy(calls = 101 + i.toLong()) }))
        val input = FrequencyCalculator().calculate(listOf(day, night))
        val examples = before.map { SqlExample(key().copy(queryid = requireNotNull(it.queryid)), exampleSql, listOf("pg_catalog", "public")) }
        val result = build(input, examples)
        assertEquals(result, build(input, examples.reversed()))
        val workload = read(result)
        assertEquals(20, workload.queries.size)
        assertTrue(workload.queries.any { it.id == ObservedWorkloadImporter.id(key().copy(queryid = 25)) })
        assertEquals(5, tree(result.manifest)["entries"].count { it["reason"].asString() == "QUERY_LIMIT" })
        assertTrue(workload.profiles.all { it.callsPerHour.size == 20 })
    }

    @Test fun `missing examples duplicate identities empty profiles and modified replay identity are errors`() {
        assertThrows(AnalysisException::class.java) { build(frequencies(), emptyList()) }
        val example = SqlExample(key(), exampleSql, listOf("pg_catalog", "public"))
        assertThrows(AnalysisException::class.java) { build(frequencies(), listOf(example, example)) }
        val profile = frequencies().profiles.single()
        assertThrows(AnalysisException::class.java) { build(frequencies().copy(profiles = listOf(profile,
            profile.copy(id = "empty", observations = profile.observations.map { it.copy(state = ObservationState.ZERO, callsPerHour = 0.0) })))) }
        val result = build(frequencies())
        val altered = result.workload + ("queries" to listOf(mapOf("id" to "edited", "sql" to exampleSql)))
        assertThrows(AnalysisException::class.java) { read(result.copy(workload = altered)) }
    }

    @Test fun `CLI help and invalid config require neither a database nor published output`() {
        val command = ImportWorkloadCommand { fail("Database must not be opened") }
        val out = StringWriter(); val err = StringWriter()
        assertEquals(0, command.execute(arrayOf("import-workload", "--help"), PrintWriter(out, true), PrintWriter(err, true)))
        assertTrue(out.toString().contains("search_path"))
        assertEquals(2, command.execute(arrayOf("import-workload", "--config", "missing"), PrintWriter(out, true), PrintWriter(err, true)))
    }

    @Test fun `coverage call counts cannot silently overflow the report contract`() {
        val before = listOf(row(0), row(0).copy(queryid = 2))
        val after = before.map { it.copy(calls = Long.MAX_VALUE) }
        val input = FrequencyCalculator().calculate(listOf(FrequencyWindow("day", 1.0, capture(0, before), capture(60, after))))
        val examples = (1L..2L).map { SqlExample(key().copy(queryid = it), exampleSql, listOf("pg_catalog", "public")) }
        assertEquals(ErrorCode.NUMERIC_ERROR, assertThrows(AnalysisException::class.java) { build(input, examples) }.error.code)
    }

    @Test fun `deadline is not downgraded to a rejected SQL or an empty workload`() {
        var ticks = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = java.time.Duration.ofNanos(20)), clock = NanoClock { ticks++ })
        val input = frequencies()
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AnalysisException::class.java) {
            ObservedWorkloadImporter(loader).build(input, listOf(SqlExample(key(), exampleSql, listOf("pg_catalog", "public"))), options, context)
        }.error.code)
    }

    @Test fun `excluded SQL text is not copied into manifests or diagnostics`() {
        val input = frequencies(query = "CREATE ROLE example PASSWORD 'secret-marker'")
        val result = ObservedWorkloadImporter(loader).build(input, emptyList(), options)
        val json = ImportJson.mapper.writeValueAsString(result.manifest)
        assertEquals("failed", tree(result.manifest)["status"].asString())
        assertTrue(json.contains("UNSUPPORTED_STATEMENT"))
        assertFalse(json.contains("secret-marker"))
    }

    private fun key() = StatisticsKey(source.sourceId, 42, 10, 1, true)
    private fun row(calls: Long, query: String = template) = StatisticsEntry(42, 10, 1, true, query, QueryTextStatus.AVAILABLE, calls, 100.0, 0)
    private fun capture(second: Long, entries: List<StatisticsEntry>) = StatisticsSnapshot(UUID.randomUUID(), source,
        Instant.parse("2026-11-11T00:00:00Z").plusSeconds(second).toString(),
        Instant.parse("2026-11-11T00:00:00Z").plusSeconds(second).toString(), epoch, epoch, entries, emptyList(), SnapshotLimits())
    private fun frequencies(query: String = template) = FrequencyCalculator().calculate(listOf(FrequencyWindow("day", 1.0,
        capture(0, listOf(row(100, query))), capture(60, listOf(row(160, query))))))
    private fun build(input: FrequencyProfiles, examples: List<SqlExample> = listOf(SqlExample(key(), exampleSql, listOf("pg_catalog", "public")))) =
        ObservedWorkloadImporter(loader).build(input, examples, options).also { it.error?.let { error -> throw AnalysisException(error) } }
    private fun tree(value: Any) = ImportJson.mapper.readTree(ImportJson.mapper.writeValueAsBytes(value))
    private fun read(result: ImportedWorkload): WorkloadInput {
        val path = directory.resolve("workload.json")
        Files.write(path, ImportJson.mapper.writeValueAsBytes(result.workload))
        return WorkloadReader().read(path)
    }
}
