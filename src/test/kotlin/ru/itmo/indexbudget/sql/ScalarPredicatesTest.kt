package ru.itmo.indexbudget.sql

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.withSqlSemantics
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.application.WorkloadReader
import java.nio.file.Path
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.JsonReportWriter
import ru.itmo.indexbudget.report.HtmlReportRenderer

class ScalarPredicatesTest {
    private val analyzer = JSqlParserSqlAnalyzer()
    private val table = TableRef("public", "records")
    private val catalog = CatalogSnapshot(listOf(CatalogTable(table, listOf(
        CatalogColumn("id", "int4", defaultOperatorClass = "pg_catalog.int4_ops"),
        CatalogColumn("value", "int4", defaultOperatorClass = "pg_catalog.int4_ops"),
        CatalogColumn("Status", "text", defaultOperatorClass = "pg_catalog.text_ops"),
    ))), searchPath = listOf("pg_catalog", "public")).withSqlSemantics()

    @Test
    fun `json null tests keep full SQL and exclude only unindexable variants`() {
        val snapshot = catalog.copy(tables = catalog.tables.map { it.copy(columns = it.columns + CatalogColumn("payload", "json")) })
        // INCLUDE adds (payload) INCLUDE (id); it too must be rejected because the key has no opclass.
        for ((nullTest, rejected) in listOf("IS NULL" to 4, "IS NOT NULL" to 3)) {
            val sql = "SELECT id FROM records WHERE payload $nullTest AND id = 1"
            val workload = resolve(sql, snapshot)
            val result = ProfileAwareCandidateGenerator().generate(workload, snapshot, RunContext())
            assertEquals(sql, workload.queries.single().sql)
            assertEquals(listOf("id"), result.candidates.single().definition.keys.map { it.column.name })
            assertEquals(2, result.diagnostics.localScopes.single().retainedColumns)
            assertEquals(rejected, result.diagnostics.localScopes.single().unindexableVariants)
            assertEquals(1, result.diagnostics.localScopes.single().deduplicatedDefinitions)
            assertEquals(1, result.diagnostics.localScopes.single().enumeratedIncludeDefinitions)
            assertEquals(0, result.diagnostics.localScopes.single().retainedIncludeDefinitions)
            val decision = result.diagnostics.decisions.single()
            assertEquals(CandidateReason.NO_BTREE_OPERATOR_CLASS, decision.reason)
            assertEquals("q", decision.queryId)
            assertEquals(table, decision.table)
            assertEquals("payload", decision.details["column"])
            assertEquals(rejected.toString(), decision.details["affected_variants"])
            assertNull(decision.candidateId)
            assertEquals(WarningCode.CANDIDATE_REJECTED, result.diagnostics.warnings.single().code)
            assertNull(result.candidates.single().estimatedBytes)
            val baseline = EvaluationResult(emptyList(), mapOf("q" to 1.0), mapOf("q" to ExplainPlan("{}")))
            val metrics = MetricsCalculatorImpl().calculate(workload.input, baseline, baseline, BudgetUsage(0, 0, null, 0))
            val empty = EvaluatedConfiguration(baseline, metrics)
            val search = SearchResult.Completed(workload.input.objective, baseline, empty, empty)
            val report = DefaultReportBuilder().build(workload.input, result, search, RunContext())
            assertTrue(JsonReportWriter().render(report).contains("NO_BTREE_OPERATOR_CLASS"))
            assertTrue(JsonReportWriter().render(report).contains("unindexable_variants"))
            assertTrue(HtmlReportRenderer().render(report).contains("Нет подходящего B-tree opclass"))
            val onlyJson = resolve("SELECT id FROM records WHERE payload $nullTest", snapshot)
            assertTrue(ProfileAwareCandidateGenerator().generate(onlyJson, snapshot, RunContext()).candidates.isEmpty())
        }
    }

    @Test
    fun `documented example remains valid input and feeds the real shortlist generator`() {
        val context = RunContext()
        val workload = WorkloadReader().read(Path.of("examples/workload-v2-predicates.json"), context)
        val snapshot = ContractFixtures.catalog().withSqlSemantics()
        val resolved = analyzer.resolve(analyzer.parse(workload, context), snapshot, context)
        assertEquals(4, resolved.queries.size)
        assertFalse(ProfileAwareCandidateGenerator().generate(resolved, snapshot, context).candidates.isEmpty())
        assertEquals(workload.queries.map { it.sql }, resolved.queries.map { it.sql })
    }

    @Test
    fun `new forms preserve one feature each and complete original SQL`() {
        val sql = "SELECT r.id FROM records r WHERE r.id IN (1, '2'::integer, NULL) " +
            "AND r.value BETWEEN -1 AND CAST(10 AS integer) AND r.\"Status\" IS NULL AND r.id IS NOT NULL"
        val workload = resolve(sql)
        val query = workload.queries.single()
        assertEquals(sql, query.sql)
        assertEquals(listOf(ComparisonOperator.IN, ComparisonOperator.BETWEEN, ComparisonOperator.IS_NULL,
            ComparisonOperator.IS_NOT_NULL), query.predicates.map { it.operator })
        assertEquals(listOf(3, 2, 0, 0), query.predicates.map { it.literalCount })
        assertEquals(setOf("id", "value", "Status"), query.referencedColumns.map { it.name }.toSet())
        assertTrue(query.predicates.all { it.rightColumn == null && it.source == PredicateSource.WHERE })
        val result = ProfileAwareCandidateGenerator().generate(workload, catalog, RunContext())
        val pairs = result.candidates.filter { it.definition.keys.size == 2 }
        assertTrue(pairs.any { it.definition.keys.map { key -> key.column.name } == listOf("id", "value") &&
            GenerationReason.EQUALITY_AND_RANGE in it.generationReasons })
        assertTrue(pairs.any { it.definition.keys.map { key -> key.column.name } == listOf("Status", "value") &&
            GenerationReason.EQUALITY_AND_RANGE in it.generationReasons })
    }

    @ParameterizedTest
    @ValueSource(strings = ["id IN (1)", "id IN (NULL, NULL)", "id IN (1, 2147483648)",
        "id IN (1, 2.5)", "\"Status\" IN ('a', NULL, CAST('b' AS text))", "id BETWEEN NULL AND 2",
        "id BETWEEN 10 AND -10", "id IS NULL", "id IS NOT NULL", "(id IN (1, 2)) AND (value IS NULL)"])
    fun `supported null and literal semantics pass without rewriting`(condition: String) {
        val sql = "SELECT id FROM records WHERE $condition"
        assertEquals(sql, resolve(sql).queries.single().sql)
    }

    @ParameterizedTest
    @ValueSource(strings = ["id NOT IN (1, 2)", "id NOT BETWEEN 1 AND 2", "id IN (SELECT id FROM records)",
        "id IN (value, 2)", "id IN (1, abs(2))", "id IN (1, $1)", "(id, value) IN ((1, 2))",
        "id + 1 BETWEEN 1 AND 2", "id BETWEEN value AND 2", "id BETWEEN 1 AND value",
        "id IN (1, 2) OR value IS NULL", "(id + 1) IS NULL", "id ISNULL", "id NOTNULL",
        "id IN (1, ~2)", "id BETWEEN ~1 AND 2", "id IN (1, CAST('2' AS missing_type))",
        "id BETWEEN 1 AND CAST(2 AS missing_type)", "id BETWEEN SYMMETRIC 1 AND 2", "id BETWEEN ASYMMETRIC 1 AND 2",
        "id IN ()", "id(+) IN (1, 2)", "id(+) BETWEEN 1 AND 2", "id(+) IS NULL", "PRIOR id IN (1, 2)"])
    fun `unsupported forms reject every item and boundary with query identity`(condition: String) {
        val error = assertThrows(AnalysisException::class.java) { resolve("SELECT id FROM records WHERE $condition") }.error
        assertEquals(ErrorCode.UNSUPPORTED_SQL, error.code, condition)
        assertEquals("q", error.queryId)
    }

    @Test
    fun `IN lists and both BETWEEN bounds consume combined WHERE JOIN complexity budget`() {
        fun list(size: Int) = (1..size).joinToString(",")
        assertEquals(256, resolve("SELECT id FROM records WHERE id IN (${list(256)})")
            .queries.single().predicates.single().literalCount)
        for (sql in listOf(
            "SELECT id FROM records WHERE id IN (${list(257)})",
            "SELECT id FROM records WHERE id IN (${list(255)}) AND value BETWEEN 1 AND 2",
            "SELECT r.id FROM records r INNER JOIN records x ON r.id = x.id WHERE r.id IN (${list(256)})",
        )) {
            val error = assertThrows(AnalysisException::class.java) { analyzer.parse(input(sql), RunContext()) }.error
            assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, error.code)
            assertEquals("q", error.queryId)
        }
        resolve("SELECT id FROM records WHERE id IN (${list(254)}) AND value BETWEEN 1 AND 2")
        resolve("SELECT id FROM records WHERE id IN (${list(255)}) AND value IS NULL")
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing IN (1, 2)", "missing BETWEEN 1 AND 2", "missing IS NULL"])
    fun `all new left columns are resolved`(condition: String) {
        assertEquals(ErrorCode.UNKNOWN_COLUMN, assertThrows(AnalysisException::class.java) {
            resolve("SELECT id FROM records WHERE $condition")
        }.error.code)
    }

    @ParameterizedTest
    @ValueSource(strings = ["id IN (1, 2)", "id BETWEEN 1 AND 2", "id IS NOT NULL"])
    fun `ambiguity is checked in every new form`(condition: String) {
        val error = assertThrows(AnalysisException::class.java) {
            resolve("SELECT r.id FROM records r INNER JOIN records s ON r.id = s.id WHERE $condition")
        }.error
        assertEquals(ErrorCode.AMBIGUOUS_COLUMN, error.code)
        assertEquals("q", error.queryId)
    }

    @Test
    fun `IN checks array common type operator not separate int equality operators`() {
        val facts = checkNotNull(catalog.semantics)
        val shadowed = catalog.copy(semantics = facts.copy(operators = facts.operators +
            CatalogOperator(99_999, "app", "=", 23, 20, isBuiltin = false)))
        assertEquals(ErrorCode.UNSUPPORTED_SQL, assertThrows(AnalysisException::class.java) {
            resolve("SELECT id FROM records WHERE id IN (1, 2147483648)", shadowed)
        }.error.code)
        resolve("SELECT id FROM records WHERE id IN (1, 2)", shadowed)
    }

    @Test
    fun `BETWEEN checks the upper operator as well as the lower`() {
        val facts = checkNotNull(catalog.semantics)
        val shadowed = catalog.copy(semantics = facts.copy(operators = facts.operators.map {
            if (it.name == "<=" && it.leftTypeOid == 23L && it.rightTypeOid == 23L) it.copy(isBuiltin = false) else it
        }))
        assertEquals(ErrorCode.UNSUPPORTED_SQL, assertThrows(AnalysisException::class.java) {
            resolve("SELECT id FROM records WHERE id BETWEEN 1 AND 2", shadowed)
        }.error.code)
    }

    @Test
    fun `IN type unification does not check equality operators for uncoerced list items`() {
        val facts = checkNotNull(catalog.semantics)
        val shadowed = catalog.copy(semantics = facts.copy(operators = facts.operators.map {
            if (it.name == "=" && it.leftTypeOid == 23L && it.rightTypeOid == 23L) it.copy(isBuiltin = false) else it
        }))
        resolve("SELECT id FROM records WHERE id IN (1, 2.5)", shadowed)
        assertEquals(ErrorCode.UNSUPPORTED_SQL, assertThrows(AnalysisException::class.java) {
            resolve("SELECT id FROM records WHERE id IN (1)", shadowed)
        }.error.code)
    }

    private fun input(sql: String) = WorkloadInput(listOf(QueryInput("q", sql)),
        listOf(Profile("day", 1.0, mapOf("q" to 1.0))), Constraints(1024, null))

    private fun resolve(sql: String, snapshot: CatalogSnapshot = catalog): ResolvedWorkload {
        val context = RunContext()
        return analyzer.resolve(analyzer.parse(input(sql), context), snapshot, context)
    }
}
