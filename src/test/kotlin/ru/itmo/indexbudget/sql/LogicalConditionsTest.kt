package ru.itmo.indexbudget.sql

import java.time.Duration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.indexbudget.candidates.*
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.withSqlSemantics
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.operators.conditional.AndExpression
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.PlainSelect
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.HtmlReportRenderer
import ru.itmo.indexbudget.report.JsonReportWriter
import java.nio.file.Path
import tools.jackson.databind.json.JsonMapper

class LogicalConditionsTest {
    private val analyzer = JSqlParserSqlAnalyzer()
    private val table = TableRef("public", "records")
    private val catalog = CatalogSnapshot(listOf(CatalogTable(table, (listOf("a", "b", "c", "d") +
        (1..100).map { "x$it" }).map { CatalogColumn(it, "int4", defaultOperatorClass = "pg_catalog.int4_ops") })),
        searchPath = listOf("pg_catalog", "public")).withSqlSemantics()

    @Test
    fun `example input and report preserve branch origins without changing index identity`() {
        val context = RunContext()
        val example = WorkloadReader().read(Path.of("examples/workload-v2-or.json"), context)
        val snapshot = ContractFixtures.catalog().withSqlSemantics()
        val parsed = analyzer.parse(example, context)
        val workload = analyzer.resolve(parsed, snapshot, context)
        val batch = ProfileAwareCandidateGenerator().generate(workload, snapshot, context)
        assertTrue(batch.candidates.all { it.sourceBranches.isNotEmpty() })
        val baseline = EvaluationResult(emptyList(), example.queries.associate { it.id to 10.0 },
            example.queries.associate { it.id to ExplainPlan("{}") })
        val metrics = MetricsCalculatorImpl().calculate(example, baseline, baseline, BudgetUsage(0, 0, null, 0))
        val empty = EvaluatedConfiguration(baseline, metrics)
        val result = SearchResult.Completed(example.objective, baseline, empty, empty)
        val report = DefaultReportBuilder().build(example, batch, result, context)
        val json = JsonMapper().readTree(JsonReportWriter().render(report))
        assertTrue(json["candidates"].all { it["source_branches"].size() > 0 })
        assertTrue(HtmlReportRenderer().render(report).contains("Ветви условий"))
        assertTrue(HtmlReportRenderer().render(report).contains("condition/L"))

        val alternatives = ProfileAwareCandidateGenerator().generate(resolve("a=1 OR a=2"), catalog, RunContext())
        val conjunctive = ProfileAwareCandidateGenerator().generate(resolve("a=1 AND a=2"), catalog, RunContext())
        assertEquals(conjunctive.candidates.single().id, alternatives.candidates.single().id)
        assertEquals(conjunctive.candidates.single().ddl, alternatives.candidates.single().ddl)
        assertTrue(conjunctive.candidates.single().sourceBranches.isEmpty())
    }

    @Test
    fun `AND OR precedence and parenthesized grouping survive as resolved trees`() {
        val a = ResolvedCondition.Predicate(0)
        val b = ResolvedCondition.Predicate(1)
        val c = ResolvedCondition.Predicate(2)
        assertEquals(ResolvedCondition.Or(a, ResolvedCondition.And(b, c)), resolve("a=1 OR b=2 AND c=3").queries.single().condition)
        assertEquals(ResolvedCondition.And(ResolvedCondition.Or(a, b), c), resolve("(a=1 OR b=2) AND c=3").queries.single().condition)
        assertEquals(ResolvedCondition.Or(a, ResolvedCondition.Or(b, c)), resolve("a=1 OR (b=2 OR c=3)").queries.single().condition)
    }

    @Test
    fun `alternative equalities yield singles but never a fictitious pair`() {
        val query = resolve("a=1 OR b=2")
        val variants = scope(query).variants
        assertEquals(setOf(listOf("a"), listOf("b")), keys(variants))
        assertEquals(setOf(CandidateSourceBranch("q", "condition/L")), variants.single {
            it.definition.keys.single().column.name == "a"
        }.sourceBranches)
        assertEquals(setOf(CandidateSourceBranch("q", "condition/R")), variants.single {
            it.definition.keys.single().column.name == "b"
        }.sourceBranches)
    }

    @Test
    fun `common AND terms pair with either alternative but alternatives do not pair together`() {
        val variants = scope(resolve("a=1 AND (b=2 OR c>3)")).variants
        assertEquals(setOf(listOf("a"), listOf("b"), listOf("c"), listOf("a", "b"),
            listOf("b", "a"), listOf("a", "c")), keys(variants))
        val ac = variants.single { it.definition.keys.map { key -> key.column.name } == listOf("a", "c") }
        assertEquals(setOf(GenerationReason.EQUALITY_AND_RANGE), ac.generationReasons)
        assertEquals(setOf("condition/L", "condition/R/R"), ac.sourceBranches.map { it.conditionPath }.toSet())
    }

    @Test
    fun `roles are checked per occurrence not merged across exclusive branches`() {
        val variants = scope(resolve("(a=1 AND b>2) OR (a>3 AND c=4)")).variants
        val pairs = variants.filter { it.definition.keys.size == 2 }
        assertEquals(setOf(listOf("a", "b"), listOf("c", "a")), keys(pairs))
        assertTrue(pairs.all { it.generationReasons == setOf(GenerationReason.EQUALITY_AND_RANGE) })
    }

    @Test
    fun `repeated predicates remain distinct and merge branch origins without double counting query IDs`() {
        val query = resolve("(a=1 AND b=2) OR (a=1 AND b=2)")
        assertEquals(4, query.queries.single().predicates.size)
        val batch = ProfileAwareCandidateGenerator().generate(query, catalog, RunContext())
        assertEquals(4, batch.candidates.size)
        assertTrue(batch.candidates.all { it.sourceQueryIds == setOf("q") })
        assertEquals(4, batch.candidates.single { it.definition.keys.map { key -> key.column.name } == listOf("a", "b") }
            .sourceBranches.size)
        val combined = query.copy(input = query.input.copy(
            queries = query.input.queries + QueryInput("z", query.input.queries.single().sql),
            profiles = listOf(Profile("p", 1.0, mapOf("q" to 1.0, "z" to 1.0)))),
            queries = query.queries + query.queries.single().copy(id = "z"))
        val merged = ProfileAwareCandidateGenerator().generate(combined, catalog, RunContext())
        assertTrue(merged.candidates.all { it.sourceQueryIds == setOf("q", "z") })
        assertTrue(merged.candidates.all { it.sourceBranches.map { source -> source.queryId }.toSet() == setOf("q", "z") })
    }

    @Test
    fun `INCLUDE inherits valid key origins without creating a false conjunction`() {
        val workload = resolve("a=1 OR b=2", projection = "a")
        val variants = scope(workload).variants
        assertTrue(variants.all { it.definition.keys.size == 1 })
        val covering = variants.single { it.definition.includedColumns.isNotEmpty() }
        assertEquals(listOf("b"), covering.definition.keys.map { it.column.name })
        assertEquals(listOf("a"), covering.definition.includedColumns.map { it.name })
        assertTrue(GenerationReason.COVERING_PROJECTION in covering.generationReasons)
        assertEquals(setOf(CandidateSourceBranch("q", "condition/R")), covering.sourceBranches)
        val batch = ProfileAwareCandidateGenerator().generate(workload, catalog, RunContext())
        assertEquals(covering.sourceBranches, batch.candidates.single { it.definition.includedColumns.isNotEmpty() }.sourceBranches)
    }

    @Test
    fun `sort sources are unconditional while WHERE branches remain exclusive`() {
        val query = resolve("a=1 OR b=2", " ORDER BY c DESC, d ASC")
        val variants = scope(query).variants
        val pairs = variants.filter { it.definition.keys.size == 2 }
        assertTrue(pairs.any { it.definition.keys.map { key -> key.column.name } == listOf("c", "d") &&
            it.generationReasons == setOf(GenerationReason.ORDER_BY_PAIR) })
        assertTrue(pairs.none { it.definition.keys.map { key -> key.column.name }.toSet() == setOf("a", "b") })
        val ac = pairs.single { it.definition.keys.map { key -> key.column.name } == listOf("a", "c") }
        assertEquals(setOf("condition/L", "order_by"), ac.sourceBranches.map { it.conditionPath }.toSet())
        assertEquals(SortDirection.DESC, ac.definition.keys.last().direction)
    }

    @ParameterizedTest
    @ValueSource(strings = ["a IN (1, NULL) OR b BETWEEN 2 AND 3", "a IS NULL OR b IS NOT NULL",
        "((a=1)) OR (((b=2 AND c=3)))", "a=1 OR a=2"])
    fun `every supported scalar form can appear in alternatives`(condition: String) {
        val workload = resolve(condition)
        assertEquals("SELECT * FROM records WHERE $condition", workload.queries.single().sql)
        assertTrue(scope(workload).variants.isNotEmpty())
    }

    @Test
    fun `join equality alternatives retain visibility source and tree`() {
        val sql = "SELECT r.a FROM records r INNER JOIN records s ON r.a=s.a OR r.b=s.b WHERE r.c=1 OR r.d=2"
        val input = input(sql)
        val context = RunContext()
        val resolved = analyzer.resolve(analyzer.parse(input, context), catalog, context)
        assertEquals(listOf(PredicateSource.WHERE, PredicateSource.WHERE, PredicateSource.JOIN, PredicateSource.JOIN),
            resolved.queries.single().predicates.map { it.source })
        assertInstanceOf(ResolvedCondition.And::class.java, resolved.queries.single().condition)
        assertTrue(scope(resolved).variants.none { it.definition.keys.map { key -> key.column.name }.toSet() == setOf("c", "d") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["a=1 OR missing=2", "a=1 OR (b=2 AND missing=3)", "a=1 OR abs(b)=2",
        "a=1 OR b IN (SELECT a FROM records)", "a=1 OR NOT b=2", "a=1 OR b IN (1, $1)",
        "a=1 OR b=CAST(1 AS missing_type)"])
    fun `a supported first alternative never hides invalid later expressions`(condition: String) {
        val error = assertThrows(AnalysisException::class.java) { resolve(condition) }.error
        assertEquals("q", error.queryId)
        assertTrue(error.code == ErrorCode.UNKNOWN_COLUMN || error.code == ErrorCode.UNSUPPORTED_SQL)
    }

    @Test
    fun `wide OR keeps all source conditions and caps generation before pairs`() {
        val condition = (1..100).joinToString(" OR ") { "x$it=$it" }
        val workload = resolve(condition)
        assertEquals(100, workload.queries.single().predicates.size)
        val scope = scope(workload)
        assertEquals(16, scope.retainedColumns.size)
        assertEquals(84, scope.excludedColumns.size)
        assertEquals(16, scope.variants.size)
        assertTrue(scope.variants.all { it.definition.keys.size == 1 })
        assertEquals(1, scope.warnings.count { it.code == WarningCode.OR_BRANCH_AWARE_GENERATION })
        assertEquals("false", scope.warnings.single { it.code == WarningCode.OR_BRANCH_AWARE_GENERATION }.details["dnf_expanded"])
    }

    @Test
    fun `exponential Boolean alternatives stay polynomial and deterministic`() {
        val workload = resolve((1..128).joinToString(" AND ") { "(a=$it OR b=$it)" })
        val scope = scope(workload)
        assertEquals(256, workload.queries.single().predicates.size)
        assertEquals(4, scope.variants.size) // 2^128 DNF terms must never be enumerated.
        assertEquals(scope, scope(workload))
        val batch = ProfileAwareCandidateGenerator().generate(workload, catalog, RunContext())
        assertEquals(4, batch.candidates.size)
        assertTrue(batch.candidates.all { it.sourceBranches.size <= 256 })
    }

    @Test
    fun `OR complexity includes all branches and both IN lists`() {
        resolve((1..256).joinToString(" OR ") { "a=$it" })
        for (condition in listOf((1..257).joinToString(" OR ") { "a=$it" },
            "a IN (${(1..128).joinToString()}) OR b IN (${(1..129).joinToString()})")) {
            val error = assertThrows(AnalysisException::class.java) { resolve(condition) }.error
            assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, error.code)
            assertEquals("q", error.queryId)
        }
    }

    @Test
    fun `AST depth and node bounds are enforced without a recursive validator`() {
        for (mode in listOf("depth", "nodes")) {
            val select = CCJSqlParserUtil.parse("SELECT a FROM records WHERE a=1") as PlainSelect
            val leaf = select.where
            var condition: Expression = leaf
            if (mode == "depth") repeat(ConditionLimits.MAX_DEPTH) { condition = ParenthesedExpressionList(condition) }
            else repeat(255) {
                var term: Expression = leaf
                repeat(3) { term = ParenthesedExpressionList(term) }
                condition = AndExpression(condition, term)
            }
            select.where = condition
            val error = assertThrows(AnalysisException::class.java) {
                JSqlParserAstValidator("q", RunContext()).validate(select)
            }.error
            assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, error.code, mode)
            assertEquals("q", error.queryId)
        }
    }

    @Test
    fun `malformed internal trees cannot silently fall back to conjunctive generation`() {
        val query = resolve("a=1 OR b=2")
        for (tree in listOf(ResolvedCondition.Predicate(0),
            ResolvedCondition.Or(ResolvedCondition.Predicate(0), ResolvedCondition.Predicate(0)), ResolvedCondition.Predicate(20))) {
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AnalysisException::class.java) {
                scope(query.copy(queries = listOf(query.queries.single().copy(condition = tree))))
            }.error.code)
        }
    }

    @Test
    fun `deadline interrupts compatibility construction within one query`() {
        val query = resolve("(a=1 OR b=2) AND (c=3 OR d=4)").queries.single()
        var checks = 0
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(10)), clock = NanoClock { checks++.toLong() })
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AnalysisException::class.java) {
            PredicateContexts(query, context)
        }.error.code)
    }

    private fun keys(variants: List<CandidateVariant>) = variants.map { it.definition.keys.map { key -> key.column.name } }.toSet()
    private fun scope(workload: ResolvedWorkload): CandidateScope {
        val scopes = mutableListOf<CandidateScope>()
        BtreeCandidateEnumerator().forEachScope(workload, RunContext(), scopes::add)
        return scopes.single()
    }
    private fun input(sql: String) = WorkloadInput(listOf(QueryInput("q", sql)),
        listOf(Profile("p", 1.0, mapOf("q" to 1.0))), Constraints(1024 * 1024, null))
    private fun resolve(condition: String, suffix: String = "", projection: String = "*"): ResolvedWorkload {
        val context = RunContext()
        return analyzer.resolve(analyzer.parse(input("SELECT $projection FROM records WHERE $condition$suffix"), context), catalog, context)
    }
}
