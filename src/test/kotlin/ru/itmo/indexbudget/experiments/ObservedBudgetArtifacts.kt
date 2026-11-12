package ru.itmo.indexbudget.experiments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.domain.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** Compare each export to the retained domain result, not just exports to one another. */
internal object ObservedBudgetArtifacts {
    private val mapper = JsonMapper()

    fun verifyReport(report: AnalysisReport, destination: Path) {
        val json = mapper.readTree(Files.readString(destination.resolve("report.json")))
        assertEquals(report.runId, json["run_id"].asString())
        assertEquals(report.inputHash, json["input_hash"].asString())
        val selectedIds = checkNotNull(report.result.selected).evaluation.candidateIds
        val selected = selectedIds.map { id -> report.candidates.candidates.single { it.id == id } }
        assertEquals(selectedIds, json["selected"]["candidate_ids"].toList().map { it.asString() })
        val exportedIndexes = json["selected_indexes"].toList()
        assertEquals(selectedIds, exportedIndexes.map { it["id"].asString() })
        val expectedDdl = selected.map { PostgresDdlRenderer().render(it.definition) }
        selected.zip(exportedIndexes).forEachIndexed { index, (candidate, exported) ->
            assertEquals(definition(candidate.definition), exported["definition"], "Definition of ${candidate.id}")
            assertEquals(expectedDdl[index], exported["ddl"].asString(), "DDL of ${candidate.id}")
        }
        for (file in listOf("report.html", "indexes.sql")) {
            val text = Files.readString(destination.resolve(file))
            assertTrue(text.contains(report.runId))
            assertTrue(text.contains(checkNotNull(report.inputHash)))
        }
        // Generated commands occupy one line each. Reject any additional command, not only CREATE INDEX.
        val sqlCommands = Files.readAllLines(destination.resolve("indexes.sql"))
            .filterNot { it.isBlank() || it.startsWith("--") }
        assertEquals(expectedDdl, sqlCommands)
    }

    fun verifySeries(result: BudgetSeriesResult, summary: JsonNode) {
        val points = summary["points"].toList()
        assertEquals(result.points.map { it.ordinal }, points.map { it["ordinal"].asInt() })
        result.points.zip(points).forEach { (point, exported) ->
            assertEquals(point.runId, exported["run_id"].asString())
            val ids = checkNotNull(point.result?.selected).evaluation.candidateIds
            assertEquals(ids, exported["selected_index_ids"].toList().map { it.asString() })
        }
    }

    private fun definition(value: IndexDefinition): JsonNode {
        fun table(value: TableRef) = mapOf("schema" to value.schema, "name" to value.name)
        fun column(value: ColumnRef) = mapOf("table" to table(value.table), "name" to value.name)
        return mapper.valueToTree(mapOf(
            "table" to table(value.table), "method" to value.method, "unique" to value.unique,
            "included_columns" to value.includedColumns.map(::column),
            "keys" to value.keys.map { key -> mapOf(
                "column" to column(key.column),
                "direction" to key.direction.name.lowercase().takeIf { value.method == "btree" },
                "nulls" to key.nulls.name.lowercase().takeIf { value.method == "btree" },
                "collation" to key.collation, "operator_class" to key.operatorClass,
            ) },
        ))
    }
}
