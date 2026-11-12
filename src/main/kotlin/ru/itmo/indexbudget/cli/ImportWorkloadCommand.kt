package ru.itmo.indexbudget.cli

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.application.ResolvedWorkloadValidator
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.postgres.JdbcAnalysisSessionFactory
import ru.itmo.indexbudget.postgres.JdbcCatalogReader
import ru.itmo.indexbudget.statistics.*
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.sql.Connection

@Component
class ImportWorkloadCommand(
    private val open: (RunContext) -> Connection = { JdbcAnalysisSessionFactory().openConnection(it) },
) {
    fun execute(args: Array<String>, out: PrintWriter, err: PrintWriter): Int {
        if (args.contentEquals(arrayOf("import-workload", "--help"))) { out.println(HELP); return 0 }
        var staging: Path? = null
        var publishing = false
        try {
            val values = linkedMapOf<String, String>()
            var index = 1
            while (index < args.size) {
                val option = args[index]
                val value = args.getOrNull(index + 1)
                if (option !in setOf("--config", "--output-dir") || option in values || value.isNullOrBlank() || value.startsWith("--"))
                    ImportJson.fail("Expected import-workload --config <path> --output-dir <new directory>.")
                values[option] = value
                index += 2
            }
            if (values.size != 2) ImportJson.fail("Both --config and --output-dir are required.")
            val configPath = Path.of(values.getValue("--config")).toAbsolutePath()
            val output = Path.of(values.getValue("--output-dir")).toAbsolutePath()
            if (Files.exists(output, NOFOLLOW_LINKS)) ImportJson.fail("Import output directory must be new.")
            val context = RunContext(limits = RunLimits()) // Import budget is independent of the future analyze preset.
            val config = ImportJson.read(configPath, 1024 * 1024, context)
            ImportJson.fields(config, setOf("schema_version", "kind", "windows", "examples", "constraints", "objective",
                "table_write_rates", "table_write_window", "analysis_mode", "candidate_ranking"))
            if (ImportJson.long(config, "schema_version") != 1L || ImportJson.text(config, "kind") != "pg_stat_statements_import_config")
                ImportJson.fail("Expected pg_stat_statements_import_config v1.")
            // The write profile is either manual or observed from exactly one explicit window.
            val writeWindow = config.get("table_write_window")?.let { node ->
                if (config.has("table_write_rates")) {
                    ImportJson.fail("Choose either manual table_write_rates or one table_write_window, not both.")
                }
                ImportJson.fields(node, setOf("before", "after"))
                val before = TableWriteSnapshotJson.read(
                    configPath.parent.resolve(ImportJson.text(node, "before")), maxBytes = 64 * 1024 * 1024, context)
                val after = TableWriteSnapshotJson.read(
                    configPath.parent.resolve(ImportJson.text(node, "after")), maxBytes = 64 * 1024 * 1024, context)
                WriteProfileCalculator().calculate(before, after, context).also { profile ->
                    val constraints = config.get("constraints")
                    val writeBudget = constraints?.get("write_budget_units_per_hour")?.takeUnless { it.isNull }
                    // An incomplete observation never silently disables an enabled write budget
                    // and is never replaced by manual values.
                    if (writeBudget != null && !profile.complete) {
                        ImportJson.fail("The observed write window is incomplete: ${profile.reasons.joinToString(",")}. " +
                            "Widen the window or drop write_budget_units_per_hour explicitly.",
                            ErrorCode.INVALID_WRITE_PROFILE)
                    }
                    if (profile.complete && profile.rates.isEmpty()) {
                        ImportJson.fail("A complete write profile cannot be empty.", ErrorCode.INVALID_WRITE_PROFILE)
                    }
                }
            }
            val windows = ImportJson.array(config, "windows")
            if (windows.size !in 1..3) ImportJson.fail("Provide one to three windows.")
            val frequencies = FrequencyCalculator().calculate(windows.map { node ->
                ImportJson.fields(node, setOf("id", "weight", "before", "after"))
                FrequencyWindow(ImportJson.text(node, "id"), ImportJson.number(node, "weight"),
                    ImportJson.snapshot(configPath.parent.resolve(ImportJson.text(node, "before")), context),
                    ImportJson.snapshot(configPath.parent.resolve(ImportJson.text(node, "after")), context))
            }, context)
            val examplesRoot = ImportJson.read(configPath.parent.resolve(ImportJson.text(config, "examples")), 1024 * 1024, context)
            ImportJson.fields(examplesRoot, setOf("schema_version", "kind", "examples"))
            if (ImportJson.long(examplesRoot, "schema_version") != 1L || ImportJson.text(examplesRoot, "kind") != "pg_stat_statements_examples")
                ImportJson.fail("Expected pg_stat_statements_examples v1.")
            val exampleNodes = ImportJson.array(examplesRoot, "examples")
            if (exampleNodes.size > 1000) ImportJson.fail("At most 1000 examples are supported.", ErrorCode.INPUT_LIMIT_EXCEEDED)
            val examples = exampleNodes.map { node ->
                ImportJson.fields(node, setOf("key", "sql", "search_path"))
                SqlExample(ImportJson.key(node.get("key")), ImportJson.text(node, "sql"),
                    ImportJson.array(node, "search_path").map { it.takeIf { value -> value.isString }?.asString()
                        ?: ImportJson.fail("search_path requires literal schema names.") })
            }
            if (writeWindow != null && writeWindow.databaseOid != frequencies.source.databaseOid) {
                ImportJson.fail("The write window must come from the observed database.")
            }
            val options = config.properties().filter { it.key in setOf("constraints", "objective", "table_write_rates", "analysis_mode", "candidate_ranking") }
                .associate { it.key to it.value }.toMutableMap()
            if (writeWindow != null) {
                // Observed rates are rows per hour, not SQL calls; they never mix with statements counts.
                options["table_write_rates"] = ImportJson.mapper.valueToTree(
                    writeWindow.rates.toSortedMap().mapValues { (_, rates) ->
                        linkedMapOf("inserts_per_hour" to rates.insertsPerHour,
                            "updates_per_hour" to rates.updatesPerHour,
                            "deletes_per_hour" to rates.deletesPerHour,
                            "source" to "observed")
                    })
                options["table_write_rates_origin"] = ImportJson.mapper.valueToTree(linkedMapOf(
                    "kind" to "observed",
                    "window" to linkedMapOf("from" to writeWindow.from, "to" to writeWindow.to,
                        "period_seconds" to writeWindow.periodSeconds),
                    "complete" to writeWindow.complete,
                    "reasons" to writeWindow.reasons.map { it.name },
                    "source" to linkedMapOf("database_oid" to writeWindow.databaseOid,
                        "postgres_version" to writeWindow.postgresVersion),
                ))
            }
            val imported = open(context).use { connection ->
                connection.isReadOnly = true
                connection.autoCommit = false
                connection.prepareStatement("SELECT oid FROM pg_catalog.pg_database WHERE datname OPERATOR(pg_catalog.=) pg_catalog.current_database()").use { statement ->
                    statement.queryTimeout = context.remainingTime().seconds.coerceAtLeast(1).toInt()
                    statement.executeQuery().use { rows ->
                        if (!rows.next() || rows.getLong(1) != frequencies.source.databaseOid)
                            ImportJson.fail("Import connection must target the observed database OID.")
                    }
                }
                val cache = mutableMapOf<Pair<Set<TableName>, List<String>>, CatalogSnapshot>()
                ObservedWorkloadImporter(ImportCatalog { tables, path, run ->
                    cache.getOrPut(tables to path) {
                        run.checkDeadline()
                        connection.prepareStatement("SELECT pg_catalog.set_config('search_path', ?, true)").use { statement ->
                            statement.queryTimeout = run.remainingTime().seconds.coerceAtLeast(1).toInt()
                            statement.setString(1, path.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
                            statement.executeQuery().use { it.next() }
                        }
                        JdbcCatalogReader(connection).readCatalog(tables, run)
                    }
                }).build(frequencies, examples, options, context)
                // Closing rolls back transaction-local context; no statements from the observed workload execute.
            }
            publishing = true
            Files.createDirectories(output.parent)
            val temp = Files.createTempDirectory(output.parent, ".import-")
            staging = temp
            val manifest = LinkedHashMap<String, Any?>(imported.manifest)
            if (writeWindow != null) {
                manifest["write_profile"] = linkedMapOf(
                    "source" to "pg_stat_user_tables",
                    "database_oid" to writeWindow.databaseOid,
                    "postgres_version" to writeWindow.postgresVersion,
                    "from" to writeWindow.from, "to" to writeWindow.to,
                    "period_seconds" to writeWindow.periodSeconds,
                    "complete" to writeWindow.complete,
                    "reasons" to writeWindow.reasons.map { it.name },
                    "rates_by_table" to writeWindow.rates.toSortedMap().mapValues { (table, rates) ->
                        linkedMapOf("inserts_per_hour" to rates.insertsPerHour,
                            "updates_per_hour" to rates.updatesPerHour,
                            "deletes_per_hour" to rates.deletesPerHour,
                            "hot_updates_per_hour" to writeWindow.hotUpdatesPerHour[table])
                    },
                    "table_faults" to writeWindow.tableFaults.toSortedMap().mapValues { it.value.name },
                    "limitations" to listOf("changed_rows_not_sql_calls",
                        "hot_updates_are_historical_diagnostics_never_index_price",
                        "pg16_targeted_reset_may_be_undetectable",
                        "observed_rows_never_mixed_with_pg_stat_statements"),
                )
            }
            Files.write(temp.resolve("import-manifest.json"), ImportJson.mapper.writeValueAsBytes(manifest))
            val input = if (imported.error == null) {
                Files.write(temp.resolve("workload.json"), ImportJson.mapper.writeValueAsBytes(imported.workload))
                WorkloadReader().read(temp.resolve("workload.json"), context).also {
                    ResolvedWorkloadValidator().validate(ResolvedWorkload(it, imported.resolvedQueries), context)
                }
            } else null
            context.checkDeadline()
            if (Files.exists(output, NOFOLLOW_LINKS)) ImportJson.fail("Import output was created concurrently.", ErrorCode.OUTPUT_DIRECTORY_NOT_EMPTY)
            Files.move(temp, output, ATOMIC_MOVE)
            staging = null
            imported.error?.let { err.println("${it.code.name}: ${it.message}"); return it.code.defaultExitCode }
            checkNotNull(input)
            out.println("workload.json + import-manifest.json: ${input.queries.size} queries, ${input.profiles.size} profiles" +
                (writeWindow?.let { window ->
                    ", write profile ${if (window.complete) "complete" else "incomplete"} over ${window.rates.size} tables from one observed window"
                } ?: ""))
            return 0
        } catch (failure: Exception) {
            val error = when (failure) {
                is AnalysisException -> failure.error
                is java.sql.SQLException -> AnalysisError(ErrorCode.CATALOG_ERROR, "Cannot read the import database context or catalog.")
                is java.io.IOException, is SecurityException -> AnalysisError(if (publishing) ErrorCode.EXPORT_FAILED else ErrorCode.INVALID_INPUT,
                    if (publishing) "Cannot publish imported workload and manifest." else "Cannot read import configuration, examples or snapshots.")
                is IllegalArgumentException -> AnalysisError(ErrorCode.INVALID_INPUT, "Invalid import metadata or configuration.")
                else -> AnalysisError(ErrorCode.INTERNAL_ERROR, "Workload import failed unexpectedly.")
            }
            err.println("${error.code.name}: ${error.message}")
            return error.code.defaultExitCode
        } finally {
            staging?.let { directory ->
                // Only our two known files in our private temporary directory; never recursive deletion.
                try {
                    Files.deleteIfExists(directory.resolve("workload.json"))
                    Files.deleteIfExists(directory.resolve("import-manifest.json"))
                    Files.deleteIfExists(directory)
                } catch (_: java.io.IOException) { /* Do not hide the original safe diagnostic. */ }
            }
        }
    }

    companion object {
        val HELP = """
            indexbudget import-workload --config <import-config.json> --output-dir <new directory>
            Reads 1–3 saved snapshot windows and explicit literal examples keyed by full statistics identity.
            Config v1: kind pg_stat_statements_import_config, windows [{id,weight,before,after}], examples path,
              constraints, optional objective/analysis_mode/candidate_ranking and exactly one write source:
              manual table_write_rates or table_write_window {before,after} from a compatible pair of
              pg_stat_user_tables_snapshot v1 files. Observed rates are changed rows per hour
              (delta * 3600 / delta_seconds), never SQL calls, never mixed with pg_stat_statements.
              An incomplete observed window is recorded honestly and never silently disables
              an enabled write budget; HOT updates stay a historical diagnostic.
            Examples v1: kind pg_stat_statements_examples, examples [{key,sql,search_path}].
            Paths are relative to the config. search_path is explicit, starts with pg_catalog, no implicit role/temp schemas.
            AST matching permits only literal substitutions for parameters, not different queries.
            At most 20 supported SQL, round-robin over profile queues ordered by calls/hour then full identity.
            Unknown frequencies are never zero-filled. Nested statements are excluded to avoid double counting.
            Saves validated workload v2 and import-manifest v1; analyze remains a separate command.
            Connection INDEXBUDGET_DATABASE_URL reads catalog only; no HypoPG, EXPLAIN, workload execution or DDL.
            Source UUID is an operator assertion, database OID must match. Historical time is not predicted latency.
        """.trimIndent()
    }
}
