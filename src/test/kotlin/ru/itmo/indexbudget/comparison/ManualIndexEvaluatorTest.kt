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
        assertEquals(prepared.candidates.map { it.id }, result.evaluation!!.candidateIds)
        assertTrue(result.admissible)
        assertEquals(2, result.metrics!!.budgetUsage.indexCount)
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
        assertEquals("failed", result.status)
        assertEquals(ErrorCode.EXPLAIN_FAILED, result.error!!.code)
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
        assertEquals(NullsOrder.FIRST, input.manualIndexes!!.single().keys.single().nulls)
        Files.writeString(path, text.replace("\"column\":\"value\"", "\"column\":\"value\",\"operator_class\":\"injected\""))
        assertThrows(AnalysisException::class.java) { WorkloadReader().read(path) }
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
