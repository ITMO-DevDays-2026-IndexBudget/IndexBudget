package ru.itmo.indexbudget.comparison

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.report.*
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

class ManualIndexEvaluatorTest {
    @TempDir lateinit var directory: Path
    private val table = ContractFixtures.recordsTable
    private val workload = ContractFixtures.resolvedWorkload()
    private val catalog = ContractFixtures.catalog()
    private val service = ManualIndexEvaluator()
    private val definition = IndexDefinition(table, listOf(IndexKey(ColumnRef(table, "value"))))
    private val baseline = ContractFixtures.baselineEvaluation()
    private val metrics = MetricsCalculatorImpl()

    @Test fun `full set uses one evaluation and does not alter automatic search results`() {
        val prepared = service.prepare(listOf(definition, IndexDefinition(table,
            listOf(IndexKey(ColumnRef(table, "created_at"))))), catalog, workload, RunContext())
        val session = FakeSession()
        val search = ContractFixtures.completedResult()
        val result = service.evaluate(prepared, workload, baseline, session, metrics, RunContext()) {}
        assertEquals(1, session.calls)
        assertEquals(2, session.evaluated.size)
        assertEquals(prepared.candidates.map { it.id }, checkNotNull(result.evaluation) { "Expected evaluation to be available" }.candidateIds)
        assertTrue(result.admissible)
        assertEquals(2, checkNotNull(result.metrics) { "Expected metrics to be available" }.budgetUsage.indexCount)
        val report = DefaultReportBuilder().build(workload.input, CandidateBatch(ContractFixtures.candidates()), search,
            RunContext()).copy(manualComparison = result)
        assertSame(search, report.result)
        val json = JsonMapper().readTree(JsonReportWriter().render(report))
        assertEquals("manual_outside_automatic_shortlist", json["manual_comparison"]["origin"].stringValue())
        assertEquals(2, json["manual_comparison"]["configuration"]["candidate_ids"].size())
        assertTrue(HtmlReportRenderer().render(report).contains("Ручной набор индексов"))
    }

    @Test fun `canonical duplicates collapse and exact real duplicates use baseline without hypotheses`() {
        val repeated = service.prepare(listOf(definition, definition), catalog, workload, RunContext())
        assertEquals(1, repeated.candidates.size)
        val existing = catalog.copy(tables = catalog.tables.map { it.copy(existingIndexes = it.existingIndexes +
            ExistingIndex("already_value", table, "btree", repeated.candidates.single().definition.keys)) })
        val prepared = service.prepare(listOf(definition), existing, workload, RunContext())
        assertTrue(prepared.candidates.isEmpty())
        assertTrue(prepared.decisions.any { it.reason == CandidateReason.EXACT_EXISTING_DUPLICATE })
        val session = FakeSession()
        val result = service.evaluate(prepared, workload, baseline, session, metrics, RunContext()) {}
        assertEquals(0, session.calls)
        assertSame(baseline, result.evaluation)
    }

    @Test fun `manual BRIN requires its own opclass and shares budgets with B-tree`() {
        val brin = definition.copy(method = "brin")
        val missing = assertThrows(AnalysisException::class.java) {
            service.prepare(listOf(brin), catalog, workload, RunContext())
        }
        assertEquals(ErrorCode.INVALID_INPUT, missing.error.code)
        assertEquals("manual_indexes.0.keys.0", missing.error.details["field"])
        val withBrin = catalog.copy(tables = catalog.tables.map { t -> t.copy(columns = t.columns.map {
            it.copy(defaultOperatorClasses = mapOf("brin" to "pg_catalog.int4_minmax_ops"))
        }) })
        val prepared = service.prepare(listOf(definition, brin, brin), withBrin, workload, RunContext())
        assertEquals(2, prepared.candidates.size)
        assertEquals(setOf("brin", "btree"), prepared.candidates.map { it.definition.method }.toSet())
        val sized = prepared.candidates.map { it.copy(estimatedBytes = 100) }
        val usage = metrics.assessBudget(sized, workload.input).usage
        assertEquals(240, usage.budgetedBytes)
        assertEquals(2, usage.indexCount)
        val units = sized.map { metrics.assessBudget(listOf(it), workload.input).usage.writeUnitsPerHour }
        assertEquals(units[0], units[1], "Equal proxy values are not claims about physical write costs")
    }

    @Test fun `unknown column is rejected before evaluation`() {
        val bad = definition.copy(keys = listOf(IndexKey(ColumnRef(table, "missing"))))
        val error = assertThrows(AnalysisException::class.java) {
            service.prepare(listOf(bad), catalog, workload, RunContext())
        }
        assertEquals(ErrorCode.UNKNOWN_COLUMN, error.error.code)
    }

    @Test fun `over budget set is measured and clearly inadmissible`() {
        val input = workload.copy(input = workload.input.copy(constraints = Constraints(0, 0.0, 0)))
        val prepared = service.prepare(listOf(definition), catalog, input, RunContext())
        val session = FakeSession()
        val result = service.evaluate(prepared, input, baseline, session, metrics, RunContext()) {}
        assertEquals(1, session.calls)
        assertEquals(setOf(BudgetViolation.STORAGE_BUDGET, BudgetViolation.WRITE_BUDGET, BudgetViolation.MAX_INDEXES), result.budgetViolations)
        assertFalse(result.admissible)
        assertNotNull(result.metrics)
    }

    @Test fun `failed evaluation has no partial vector and preserves safe error`() {
        val prepared = service.prepare(listOf(definition), catalog, workload, RunContext())
        val result = service.evaluate(prepared, workload, baseline, FakeSession(fail = true), metrics, RunContext()) {}
        assertEquals(ManualComparisonStatus.FAILED, result.status)
        assertEquals(ErrorCode.EXPLAIN_FAILED, checkNotNull(result.error) { "Expected error to be available" }.code)
        assertNull(result.evaluation)
        assertNull(result.metrics)
        assertFalse(result.admissible)
    }

    @Test fun `manual JSON is additive strict and preserves physical identifiers`() {
        val source = Files.readString(Path.of("examples/workload-v2.json"))
        // Use the existing example's exact root and add only the new optional field.
        val text = source.trim().dropLast(1) + ",\"manual_indexes\":[{\"schema\":\"public\",\"table\":\"records\",\"keys\":[{\"column\":\"value\",\"direction\":\"desc\"}]}]}"
        val path = directory.resolve("input.json")
        Files.writeString(path, text)
        val input = WorkloadReader().read(path)
        assertEquals(NullsOrder.FIRST, checkNotNull(input.manualIndexes) { "Expected manualIndexes to be available" }.single().keys.single().nulls)
        Files.writeString(path, text.replace("\"column\":\"value\"", "\"column\":\"value\",\"operator_class\":\"injected\""))
        assertThrows(AnalysisException::class.java) { WorkloadReader().read(path) }
    }

    @Test fun `manual identifiers allow whitespace and preserve exact Unicode values`() {
        val path = directory.resolve("identifiers.json")
        val source = Files.readString(Path.of("examples/workload-v2.json")).trim().dropLast(1)
        for (name in listOf(" ", "\t", " Mixed Имя ", "😀")) {
            val quoted = JsonMapper().writeValueAsString(name)
            Files.writeString(path, source + ",\"manual_indexes\":[{\"schema\":$quoted,\"table\":$quoted,\"keys\":[{\"column\":$quoted}]}]}")
            val definition = checkNotNull(WorkloadReader().read(path).manualIndexes) { "Expected manualIndexes to be available" }.single()
            assertEquals(TableRef(name, name), definition.table)
            assertEquals(name, definition.keys.single().column.name)
        }
        for (field in listOf("schema", "table", "column")) {
            for (bad in listOf("\"\"", "\"\\u0000\"", "\"\\ud800\"")) {
                val values = listOf("schema", "table", "column").associateWith { if (it == field) bad else "\"valid\"" }
                Files.writeString(path, source + ",\"manual_indexes\":[{\"schema\":${values.getValue("schema")},\"table\":${values.getValue("table")},\"keys\":[{\"column\":${values.getValue("column")}}]}]}")
                val error = assertThrows(AnalysisException::class.java) { WorkloadReader().read(path) }.error
                assertEquals(ErrorCode.INVALID_INPUT, error.code)
            }
        }
    }

    @Test fun `completed manual model requires full evaluation and metrics`() {
        assertThrows(IllegalArgumentException::class.java) { ManualComparison(ManualComparisonStatus.COMPLETED) }
        assertThrows(IllegalArgumentException::class.java) { ManualComparison(ManualComparisonStatus.FAILED) }
    }

    private class FakeSession(private val fail: Boolean = false) : AnalysisSession {
        var calls = 0
        var evaluated = emptyList<IndexCandidate>()
        override fun readCatalog(tables: Set<TableName>, context: RunContext) = error("No catalog reads")
        override fun estimateSize(candidate: IndexCandidate, context: RunContext) = 100L
        override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload, context: RunContext): EvaluationResult {
            calls++
            evaluated = candidates
            if (fail) throw AnalysisException(AnalysisError(ErrorCode.EXPLAIN_FAILED, "Synthetic evaluation failure"))
            val costs = workload.input.queries.associate { it.id to 50.0 }
            return EvaluationResult(candidates.map { it.id }, costs, costs.mapValues { ExplainPlan("[]") })
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
