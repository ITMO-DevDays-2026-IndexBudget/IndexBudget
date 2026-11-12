package ru.itmo.indexbudget.examples

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.application.BudgetSeriesReader
import ru.itmo.indexbudget.application.ResolvedWorkloadValidator
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.withSqlSemantics
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** Static library checks; actual seed/catalog/plans are verified separately against PostgreSQL. */
class DemoScenarioExamplesTest {
    private val root = Path.of("demo/scenarios")
    private val metadata = JsonMapper().readTree(Files.readString(root.resolve("catalog.json")))
    private val analyzer = JSqlParserSqlAnalyzer()

    @Test
    fun `all examples have metadata complete profiles write rates and valid supported SQL`() {
        val context = RunContext()
        val input = WorkloadReader().read(root.resolve(metadata["workload"].asString()), context)
        val resolved = analyzer.resolve(analyzer.parse(input, context), catalog(), context)
        ResolvedWorkloadValidator().validate(resolved, context)
        assertEquals(9, resolved.queries.size)
        assertEquals(7, resolved.queries.count { it.statementKind == StatementKind.SELECT })
        assertEquals(1, resolved.queries.count { it.statementKind == StatementKind.UPDATE })
        assertEquals(1, resolved.queries.count { it.statementKind == StatementKind.DELETE })
        assertEquals(input.queries.map { it.sql }, resolved.queries.map { it.sql })
        val ids = input.queries.map { it.id }
        assertEquals(ids, metadata["examples"].toList().map { it["query_id"].asString() })
        for (example in metadata["examples"]) {
            for (field in listOf("name", "purpose", "syntax_hint")) assertFalse(example[field].asString().isBlank())
        }
        assertEquals(metadata["profiles"].properties().map { it.key }.toSet(), input.profiles.map { it.id }.toSet())
        assertTrue(input.profiles.all { it.callsPerHour.keys == ids.toSet() })
        assertTrue(input.profiles.flatMap { it.callsPerHour.values }.all { it in 0.0..1_000_000.0 && it % 1.0 == 0.0 })
        assertEquals(setOf("public.records", "public.groups"), input.tableWriteRates?.keys)
        assertTrue(input.tableWriteRates.orEmpty().values.all { it.source == WriteRateSource.MANUAL })
        assertEquals(mapOf("records_by_group" to 1.05), input.constraints.queryCostGuards)
        assertEquals(2, input.manualIndexes?.size)
        assertTrue(input.constraints.maxIndexes >= 2)
        val candidates = ProfileAwareCandidateGenerator().generate(resolved, catalog(), context).candidates
        assertTrue(candidates.any { it.definition.includedColumns.isNotEmpty() })
        assertFalse(candidates.any { it.definition.includedColumns.isNotEmpty() &&
            it.sourceQueryIds.any { id -> id in setOf("update_payload", "delete_recent") } })
        assertTrue(Files.isRegularFile(Path.of(metadata["dataset"]["seed"].asString())))
        assertTrue(metadata["dataset"]["nullable_columns"].isEmpty)
        assertEquals("configuration_only_not_measurements", metadata["benchmark_handoff"]["status"].asString())
    }

    @Test
    fun `published budget series preserve the same workload and keep independent budget axes`() {
        val input = WorkloadReader().read(root.resolve("workload.json"))
        val requests = metadata["series"].toList().map { BudgetSeriesReader().read(root.resolve(it.asString())) }
        assertEquals(setOf(SeriesBudget.STORAGE, SeriesBudget.WRITE), requests.map { it.budget }.toSet())
        for (request in requests) {
            request.validate(input)
            assertEquals(java.math.BigDecimal.ZERO, request.values.first())
            for (value in request.values) {
                val point = request.inputAt(input, value)
                assertEquals(input.queries, point.queries)
                assertEquals(input.profiles, point.profiles)
                assertEquals(input.manualIndexes, point.manualIndexes)
                assertEquals(input.constraints.queryCostGuards, point.constraints.queryCostGuards)
                if (request.budget == SeriesBudget.STORAGE)
                    assertEquals(input.constraints.writeBudgetUnitsPerHour, point.constraints.writeBudgetUnitsPerHour)
                else assertEquals(input.constraints.storageBudgetBytes, point.constraints.storageBudgetBytes)
            }
        }
    }

    @Test
    fun `unsupported teaching examples expose their documented code and query identity`() {
        for (example in metadata["unsupported_examples"]) {
            val id = example["id"].asString()
            val input = WorkloadInput(listOf(QueryInput(id, example["sql"].asString())),
                listOf(Profile("p", 1.0, mapOf(id to 1.0))), Constraints(0, null))
            val error = assertThrows(AnalysisException::class.java) {
                val context = RunContext()
                analyzer.resolve(analyzer.parse(input, context), catalog(), context)
            }.error
            assertEquals(example["error_code"].asString(), error.code.name, id)
            assertEquals(id, error.queryId)
            assertFalse(example["reason"].asString().isBlank())
        }
    }

    private fun catalog(): CatalogSnapshot {
        val records = TableRef("public", "records")
        val groups = TableRef("public", "groups")
        fun column(name: String, type: String, opclass: String) =
            CatalogColumn(name, type, nullable = false, defaultOperatorClass = "pg_catalog.$opclass")
        return CatalogSnapshot(listOf(
            CatalogTable(records, listOf(column("id", "int8", "int8_ops"), column("group_id", "int4", "int4_ops"),
                column("value", "int4", "int4_ops"), column("created_at", "timestamp", "timestamp_ops"),
                column("payload", "text", "text_ops")), statisticsAvailable = true),
            CatalogTable(groups, listOf(column("id", "int4", "int4_ops"), column("category", "int4", "int4_ops"),
                column("name", "text", "text_ops")), statisticsAvailable = true),
        ), searchPath = listOf("pg_catalog", "public")).withSqlSemantics()
    }
}
