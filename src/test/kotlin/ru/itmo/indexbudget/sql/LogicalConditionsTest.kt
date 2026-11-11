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
    fun `reports associate exact origins with candidate IDs and selected indexes and escape query IDs`() {
        val specialId = "q<&\"'>"
        val queries = listOf(QueryInput(specialId, "SELECT * FROM records WHERE a=1 OR b=2"),
            QueryInput("plain", "SELECT * FROM records WHERE c=3"))
        val input = WorkloadInput(queries, listOf(Profile("p", 1.0, queries.associate { it.id to 1.0 })),
            Constraints(1024 * 1024, null))
        val context = RunContext()
        val resolved = analyzer.resolve(analyzer.parse(input, context), catalog, context)
        val generated = ProfileAwareCandidateGenerator().generate(resolved, catalog, context)
        fun id(column: String) = CanonicalIndex.id(BtreeCandidateCanonicalizer(catalog, context).canonicalize(
            IndexDefinition(table, listOf(IndexKey(ColumnRef(table, column))))))
        val expected = mapOf(id("a") to listOf(specialId to "condition/L"),
            id("b") to listOf(specialId to "condition/R"), id("c") to emptyList())
        val batch = generated.copy(candidates = generated.candidates.map { it.copy(estimatedBytes = 100, budgetedBytes = 120) })
        val chosen = batch.candidates.filter { it.id in setOf(id("a"), id("c")) }
        assertEquals(2, chosen.size)
        val baseline = EvaluationResult(emptyList(), queries.associate { it.id to 10.0 },
            queries.associate { it.id to ExplainPlan("{}") })
        val evaluation = baseline.copy(candidateIds = chosen.map { it.id }.sorted(),
            costByQueryId = queries.associate { it.id to 5.0 })
        val calculator = MetricsCalculatorImpl()
        val selected = EvaluatedConfiguration(evaluation,
            calculator.calculate(input, baseline, evaluation, calculator.assessBudget(chosen, input).usage))
        val report = DefaultReportBuilder().build(input, batch,
            SearchResult.Completed(input.objective, baseline, selected, selected), context)
        val json = JsonMapper().readTree(JsonReportWriter().render(report))
        for (field in listOf("candidates", "selected_indexes")) {
            val actual = json[field].associate { candidate -> candidate["id"].asString() to
                candidate["source_branches"].toList().map {
                    assertTrue(it.has("query_id") && it.has("condition_path"), it.toString())
                    it["query_id"].asString() to it["condition_path"].asString()
                } }
            assertEquals(if (field == "candidates") expected else expected.filterKeys { it in evaluation.candidateIds }, actual, field)
        }
        val html = HtmlReportRenderer().render(report)
        val escapedId = "q&lt;&amp;&quot;&#39;&gt;"
        assertFalse(html.contains(specialId))
        for ((candidateId, origins) in expected) {
            if (candidateId in evaluation.candidateIds) {
                val selectedCard = Regex("<article class=\"index-card\" data-selected-index=\"$candidateId\">.*?</article>",
                    RegexOption.DOT_MATCHES_ALL).findAll(html).single().value
                val branchValues = Regex("<dt>Ветви условий</dt><dd>(.*?)</dd>").findAll(selectedCard)
                    .map { it.groupValues[1] }.toList()
                assertEquals(origins.map { "$escapedId: ${it.second}" }, branchValues)
                assertFalse(html.contains("$candidateId /"), "Selected candidates have one detailed catalog entry")
            } else {
                val row = Regex("<tr>.*?</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html)
                    .map { it.value }.single { it.contains("$candidateId /") }
                val branchCell = Regex("<td>.*?</td>", RegexOption.DOT_MATCHES_ALL).findAll(row).last().value
                assertEquals("<td>" + (origins.singleOrNull()?.let { "$escapedId: ${it.second}" }
                    ?: "Нет OR-происхождения") + "</td>", branchCell)
            }
        }
        val card = Regex("<article class=\"index-card\" data-selected-index=\"${id("a")}\">.*?</article>",
            RegexOption.DOT_MATCHES_ALL).find(html)?.value
        assertNotNull(card)
        assertTrue(card!!.contains("<dt>Ветви условий</dt><dd>$escapedId: condition/L</dd>"))
        assertFalse(card.contains("condition/R"))
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
    fun `three physical tables retain left associated WHERE and JOIN trees and exact scoped origins`() {
        val second = TableRef("public", "second")
        val third = TableRef("public", "third")
        val snapshot = catalog.copy(tables = listOf(catalog.tables.single(),
            catalog.tables.single().copy(ref = second), catalog.tables.single().copy(ref = third)))
        val sql = "SELECT * FROM records r INNER JOIN second s ON r.a=s.a OR r.b=s.b " +
            "INNER JOIN third t ON s.a=t.a OR s.b=t.b WHERE r.c=1 OR s.c=2"
        val context = RunContext()
        val resolved = analyzer.resolve(analyzer.parse(input(sql), context), snapshot, context)
        fun leaf(index: Int) = ResolvedCondition.Predicate(index)
        assertEquals(ResolvedCondition.And(ResolvedCondition.And(ResolvedCondition.Or(leaf(0), leaf(1)),
            ResolvedCondition.Or(leaf(2), leaf(3))), ResolvedCondition.Or(leaf(4), leaf(5))),
            resolved.queries.single().condition)
        assertEquals(listOf(PredicateSource.WHERE, PredicateSource.WHERE) + List(4) { PredicateSource.JOIN },
            resolved.queries.single().predicates.map { it.source })
        val scopes = mutableListOf<CandidateScope>()
        BtreeCandidateEnumerator().forEachScope(resolved, context, scopes::add)
        val paths = listOf("condition/L/L/L", "condition/L/L/R", "condition/L/R/L",
            "condition/L/R/R", "condition/R/L", "condition/R/R")
        val expected = mapOf(
            table to mapOf(listOf("a") to listOf(2), listOf("b") to listOf(3), listOf("c") to listOf(0),
                listOf("a", "c") to listOf(0, 2), listOf("c", "a") to listOf(0, 2),
                listOf("b", "c") to listOf(0, 3), listOf("c", "b") to listOf(0, 3)),
            second to mapOf(listOf("a") to listOf(2, 4), listOf("b") to listOf(3, 5), listOf("c") to listOf(1),
                listOf("a", "b") to listOf(2, 3, 4, 5), listOf("b", "a") to listOf(2, 3, 4, 5),
                listOf("a", "c") to listOf(1, 2, 4), listOf("c", "a") to listOf(1, 2, 4),
                listOf("b", "c") to listOf(1, 3, 5), listOf("c", "b") to listOf(1, 3, 5)),
            third to mapOf(listOf("a") to listOf(4), listOf("b") to listOf(5)))
        assertEquals(expected.mapValues { (_, variants) -> variants.mapValues { (_, indices) ->
            indices.map { CandidateSourceBranch("q", paths[it]) }.toSet() } }, scopes.associate { scope ->
            scope.table to scope.variants.associate { variant -> variant.definition.keys.map { it.column.name } to variant.sourceBranches } })
        assertEquals(1, scopes.flatMap { it.warnings }.count { it.code == WarningCode.OR_BRANCH_AWARE_GENERATION })
        val batch = ProfileAwareCandidateGenerator().generate(resolved, snapshot, RunContext())
        assertEquals(1, batch.diagnostics.warnings.count { it.code == WarningCode.OR_BRANCH_AWARE_GENERATION })
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
    fun `real parser accepts exact condition depth and node boundaries and rejects one beyond`() {
        val depthBoundary = "(".repeat(511) + "a=1" + ")".repeat(511)
        // 256 leaves + 255 AND nodes + 512 leaf wrappers + one outer wrapper = 1024.
        val nodesBoundary = "(" + (1..256).joinToString(" AND ") { "((a=$it))" } + ")"
        for ((name, condition) in listOf("depth" to depthBoundary, "nodes" to nodesBoundary)) {
            val sql = "SELECT * FROM records WHERE $condition"
            val parsed = analyzer.parse(input(sql), RunContext())
            assertEquals(sql, parsed.input.queries.single().sql, name)
            val error = assertThrows(AnalysisException::class.java) {
                analyzer.parse(input("SELECT * FROM records WHERE ($condition)"), RunContext())
            }.error
            assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, error.code, name)
            assertEquals("q", error.queryId, name)
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
