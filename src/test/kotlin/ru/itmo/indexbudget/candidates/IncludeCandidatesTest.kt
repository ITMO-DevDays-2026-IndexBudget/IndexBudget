package ru.itmo.indexbudget.candidates

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.CandidateGenerationFixtures
import ru.itmo.indexbudget.fixtures.withSqlSemantics
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer

class IncludeCandidatesTest {
    private val table = TableRef("public", "records")
    private fun col(name: String) = ColumnRef(table, name)
    private val catalog = CatalogSnapshot(listOf(CatalogTable(table,
        listOf("a", "b", "c", "d", "x\";--").map { CatalogColumn(it, "integer", defaultOperatorClass = "pg_catalog.int4_ops") })))
    private fun definition(vararg payload: String) = IndexDefinition(table, listOf(IndexKey(col("a"))),
        includedColumns = payload.map(::col))
    private fun canonical(value: IndexDefinition) = BtreeCandidateCanonicalizer(catalog, RunContext()).canonicalize(value)
    private fun query(projection: Set<ColumnRef> = setOf(col("b"), col("c")), wildcard: Boolean = false) = ResolvedQuery(
        "q", "SELECT b, c FROM public.records WHERE a = 1", setOf(table), projection + col("a"),
        listOf(ResolvedPredicate(col("a"), ComparisonOperator.EQUAL, PredicateSource.WHERE)), emptyList(),
        projection, wildcard)
    private fun generate(query: ResolvedQuery) = ProfileAwareCandidateGenerator().generate(
        CandidateGenerationFixtures.workload(listOf(query)), catalog, RunContext())

    @Test
    fun `plain IDs retain the pre INCLUDE wire encoding`() {
        // Frozen SHA-256 values from the length-prefixed encoding in main 362de39, before #79.
        assertEquals("a17ba1a3bcfd96a8dce7ea0ce2a00ece7fde77627c58aad5bbbf09f829e68e42",
            CanonicalIndex.id(canonical(definition())))
        assertEquals("29e7b5d1e1d587d2c9a62c65dc50629233bae5f5040b95b4cb7ebfbc64675e05",
            CanonicalIndex.id(canonical(IndexDefinition(table,
                listOf(IndexKey(col("a")), IndexKey(col("b"), SortDirection.DESC))))))
    }

    @Test
    fun `payload has canonical set order but is distinct from keys and from a plain index`() {
        val covering = canonical(definition("b", "c"))
        assertEquals(covering, canonical(definition("c", "b")))
        assertEquals(CanonicalIndex.id(covering), CanonicalIndex.id(canonical(definition("c", "b"))))
        assertNotEquals(CanonicalIndex.id(covering), CanonicalIndex.id(canonical(definition())))
        assertNotEquals(CanonicalIndex.id(canonical(definition("b"))), CanonicalIndex.id(canonical(
            IndexDefinition(table, listOf(IndexKey(col("a")), IndexKey(col("b")))))))
        assertTrue(PostgresDdlRenderer().render(covering).endsWith(" INCLUDE (\"b\", \"c\");"))
        assertTrue(PostgresDdlRenderer().render(canonical(definition("x\";--"))).endsWith(" INCLUDE (\"x\"\";--\");"))
    }

    @Test
    fun `payload rejects too many repeated foreign key and unknown columns`() {
        for (names in listOf(listOf("b", "c", "d"), listOf("b", "b"), listOf("a"))) {
            assertThrows(IllegalArgumentException::class.java) { definition(*names.toTypedArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            definition().copy(includedColumns = listOf(ColumnRef(TableRef("other", "records"), "b")))
        }
        assertEquals(ErrorCode.CATALOG_ERROR, assertThrows(AnalysisException::class.java) {
            canonical(definition("absent"))
        }.error.code)
        assertThrows(IllegalArgumentException::class.java) { PostgresDdlRenderer().render(
            canonical(definition("b")).copy(includedColumns = listOf(col("bad\u0000name")))) }
    }

    @Test
    fun `only exact payload match removes candidate including reordered real payload`() {
        val normal = canonical(definition())
        val cover = canonical(definition("b", "c"))
        val existing = ExistingIndex("real_cover", table, "btree", normal.keys,
            includedColumns = listOf(col("c"), col("b")))
        val scope = CandidateScope("q", table, 1, listOf(col("a")), emptyList(),
            listOf(normal, cover, canonical(definition("b"))).map { CandidateVariant(it, setOf("q"), setOf(GenerationReason.SINGLE_COLUMN)) },
            emptyList(), emptyList())
        fun against(index: ExistingIndex) = BtreeCandidateCanonicalizer(
            CatalogSnapshot(listOf(catalog.tables.single().copy(existingIndexes = listOf(index)))), RunContext()).canonicalize(scope)
        val result = against(existing)
        assertEquals(setOf(normal, canonical(definition("b"))), result.candidates.map { it.definition }.toSet())
        assertEquals(CanonicalIndex.id(cover), result.decisions.single().candidateId)
        for (ineligible in listOf(existing.copy(valid = false), existing.copy(ready = false),
            existing.copy(accessible = false), existing.copy(predicate = "a > 0"), existing.copy(expressions = listOf("a+1")))) {
            assertEquals(3, against(ineligible).candidates.size)
        }
        assertEquals(setOf(cover, canonical(definition("b"))), against(existing.copy(includedColumns = emptyList()))
            .candidates.map { it.definition }.toSet())
    }

    @Test
    fun `explicit resolved projection adds one covering variant with separate provenance and counters`() {
        val result = generate(query())
        assertEquals(2, result.candidates.size)
        val cover = result.candidates.single { it.definition.includedColumns.isNotEmpty() }
        assertEquals(listOf(col("b"), col("c")), cover.definition.includedColumns)
        assertEquals(setOf("q"), cover.sourceQueryIds)
        assertTrue(GenerationReason.COVERING_PROJECTION in cover.generationReasons)
        assertNull(cover.estimatedBytes)
        assertEquals(1, result.diagnostics.localScopes.single().enumeratedIncludeDefinitions)
        assertEquals(1, result.diagnostics.localScopes.single().retainedIncludeDefinitions)
        assertEquals(result.candidates, generate(query(projection = linkedSetOf(col("c"), col("b")))).candidates)
    }

    @Test
    fun `wildcards oversized payload and uncovered predicates never produce arbitrary covering subsets`() {
        assertTrue(generate(query(wildcard = true)).candidates.all { it.definition.includedColumns.isEmpty() })
        val oversized = generate(query(setOf(col("b"), col("c"), col("d"))))
        assertTrue(oversized.candidates.all { it.definition.includedColumns.isEmpty() })
        assertEquals(CandidateReason.INCLUDE_PAYLOAD_LIMIT, oversized.diagnostics.decisions.single().reason)
        assertEquals("1", oversized.diagnostics.decisions.single().details["excluded_variants"])
        val uncovered = query().copy(referencedColumns = query().referencedColumns + col("d"))
        assertTrue(generate(uncovered).candidates.all { it.definition.includedColumns.isEmpty() })
        assertTrue(generate(query(setOf(col("a")))).candidates.all { it.definition.includedColumns.isEmpty() })
        assertTrue(generate(query().copy(predicates = emptyList())).candidates.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["*", "r.*", "r.*, r.b", "o.*, r.b", "r.b, o.*"])
    fun `resolver wildcard disables covering for the entire join query`(projection: String) {
        val other = TableRef("public", "other_records")
        val sqlCatalog = CatalogSnapshot(listOf(catalog.tables.single(),
            CatalogTable(other, listOf("a", "b").map {
                CatalogColumn(it, "integer", defaultOperatorClass = "pg_catalog.int4_ops")
            }))).withSqlSemantics()
        val from = "FROM public.records r INNER JOIN public.other_records o ON r.a = o.a WHERE r.a = 1"
        val context = RunContext()
        val analyzer = JSqlParserSqlAnalyzer()
        fun resolve(sql: String): ResolvedWorkload {
            val input = WorkloadInput(listOf(QueryInput("q", sql)),
                listOf(Profile("p", 1.0, mapOf("q" to 1.0))), Constraints(1_000_000, null))
            return analyzer.resolve(analyzer.parse(input, context), sqlCatalog, context)
        }
        // The same JOIN with explicit projection can generate INCLUDE for both tables.
        val explicit = resolve("SELECT r.b, o.b $from")
        assertFalse(explicit.queries.single().selectsAllColumns)
        val control = ProfileAwareCandidateGenerator().generate(explicit, sqlCatalog, context)
        assertEquals(setOf(table, other), control.candidates.filter { it.definition.includedColumns.isNotEmpty() }
            .map { it.definition.table }.toSet())

        val sql = "SELECT $projection $from"
        val resolved = resolve(sql)
        assertEquals(sql, resolved.queries.single().sql)
        assertTrue(resolved.queries.single().selectsAllColumns)
        val batch = ProfileAwareCandidateGenerator().generate(resolved, sqlCatalog, context)
        assertEquals(setOf(table, other), batch.candidates.map { it.definition.table }.toSet())
        assertTrue(batch.candidates.all { it.definition.includedColumns.isEmpty() })
        assertTrue(batch.diagnostics.localScopes.all { it.enumeratedIncludeDefinitions == 0 })
    }

    @ParameterizedTest
    @ValueSource(strings = ["a IN (1, 2, NULL)", "a BETWEEN 1 AND 2", "a IS NULL", "a IS NOT NULL"])
    fun `new predicates allow payload without Btree opclass while rejecting it as a key`(predicate: String) {
        val sqlCatalog = catalog.copy(tables = catalog.tables.map { table -> table.copy(
            columns = table.columns.map { if (it.name == "b") it.copy(defaultOperatorClass = null) else it },
        ) }).withSqlSemantics()
        val sql = "SELECT b FROM public.records WHERE $predicate AND b IS NULL"
        val input = WorkloadInput(listOf(QueryInput("q", sql)),
            listOf(Profile("p", 1.0, mapOf("q" to 1.0))), Constraints(1_000_000, null))
        val context = RunContext()
        val analyzer = JSqlParserSqlAnalyzer()
        val resolved = analyzer.resolve(analyzer.parse(input, context), sqlCatalog, context)
        val batch = ProfileAwareCandidateGenerator().generate(resolved, sqlCatalog, context)

        assertEquals(sql, resolved.queries.single().sql)
        val covering = batch.candidates.single { it.definition.includedColumns.isNotEmpty() }
        assertEquals(listOf(col("a")), covering.definition.keys.map { it.column })
        assertEquals(listOf(col("b")), covering.definition.includedColumns)
        assertTrue(batch.candidates.all { candidate -> candidate.definition.keys.none { it.column == col("b") } })
        assertTrue(batch.diagnostics.localScopes.single().unindexableVariants > 0)
        assertEquals("b", batch.diagnostics.decisions.single {
            it.reason == CandidateReason.NO_BTREE_OPERATOR_CLASS
        }.details["column"])
    }

    @Test
    fun `local and global caps count covering variants without changing key arity`() {
        val columns = listOf("a", "b", "c", "d").map(::col)
        val wide = query(columns.toSet()).copy(referencedColumns = columns.toSet(),
            predicates = columns.map { ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE) })
        val batch = generate(wide)
        val scope = batch.diagnostics.localScopes.single()
        assertEquals(28, scope.enumeratedDefinitions) // Four singles, twelve plain pairs, twelve covering pairs.
        assertEquals(12, scope.enumeratedIncludeDefinitions)
        assertEquals(8, scope.retainedIncludeDefinitions)
        assertEquals(24, scope.retainedDefinitions)
        assertEquals(16, batch.candidates.size)
        assertEquals(8, batch.reserve.size)
        assertEquals("12", batch.diagnostics.decisions.single { it.reason == CandidateReason.LOCAL_CANDIDATE_LIMIT }
            .details["include_before"])
        val pool = batch.candidates + batch.reserve
        assertEquals(24, pool.map { it.id }.toSet().size)
        assertEquals(8, pool.count { it.definition.includedColumns.isNotEmpty() })
        assertTrue(pool.all { it.definition.keys.size in 1..2 && it.definition.includedColumns.size <= 2 })
        assertTrue(pool.filter { it.definition.includedColumns.isNotEmpty() }.all { it.definition.keys.size == 2 })
        assertTrue(batch.diagnostics.warnings.any { it.code == WarningCode.GLOBAL_CANDIDATE_LIMIT })
        assertFalse(batch.diagnostics.decisions.any { it.reason == CandidateReason.GLOBAL_CANDIDATE_LIMIT })
    }

    @Test
    fun `direction variants respect the separate INCLUDE enumeration limit`() {
        val columns = listOf("a", "b", "c", "d").map(::col)
        // Exercise the bounded structural contract even for repeated resolved directions.
        val wide = query(columns.toSet()).copy(referencedColumns = columns.toSet(),
            predicates = columns.map { ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE) },
            orderBy = columns.flatMap { listOf(OrderByColumn(it), OrderByColumn(it, SortDirection.DESC)) })
        val scopes = mutableListOf<CandidateScope>()
        BtreeCandidateEnumerator().forEachScope(CandidateGenerationFixtures.workload(listOf(wide)), RunContext(), scopes::add)
        val scope = scopes.single()
        assertEquals(68, scope.enumeratedDefinitions) // 4 + 12*4 ordinary plus at most 16 INCLUDE.
        assertEquals(16, scope.variants.count { it.definition.includedColumns.isNotEmpty() })
        assertEquals("32", scope.decisions.single { it.reason == CandidateReason.INCLUDE_VARIANT_LIMIT }
            .details["excluded_variants"])
    }

    @Test
    fun `covering duplicates merge query provenance without leaking to plain variants`() {
        val q = query()
        val result = ProfileAwareCandidateGenerator().generate(CandidateGenerationFixtures.workload(listOf(q, q.copy(id = "q2"))),
            catalog, RunContext())
        assertEquals(2, result.candidates.size)
        result.candidates.forEach { assertEquals(setOf("q", "q2"), it.sourceQueryIds) }
        assertFalse(GenerationReason.COVERING_PROJECTION in result.candidates.single { it.definition.includedColumns.isEmpty() }.generationReasons)
    }
}
