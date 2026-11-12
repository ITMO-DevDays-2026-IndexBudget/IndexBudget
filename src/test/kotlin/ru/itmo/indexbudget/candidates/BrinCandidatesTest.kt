package ru.itmo.indexbudget.candidates

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*

class BrinCandidatesTest {
    private val table = TableRef("odd schema", "events")
    private val column = ColumnRef(table, "at\"time")
    private val key = IndexKey(column)
    private val brin = IndexDefinition(table, listOf(key), method = "brin")
    private val columns = listOf(CatalogColumn(column.name, "int4", defaultOperatorClass = "pg_catalog.int4_ops",
        defaultOperatorClasses = mapOf("brin" to "pg_catalog.int4_minmax_ops")))
    private fun canonicalizer(existing: List<ExistingIndex> = emptyList(), columns: List<CatalogColumn> = this.columns) =
        BtreeCandidateCanonicalizer(CatalogSnapshot(listOf(CatalogTable(table, columns, existingIndexes = existing))), RunContext())
    private fun scope(vararg definitions: IndexDefinition) = CandidateScope("q", table, 1, listOf(column), emptyList(),
        definitions.map { CandidateVariant(it, setOf("q"), setOf(GenerationReason.BRIN_RANGE)) }, emptyList(), emptyList())

    @Test
    fun `method participates in stable identity and BRIN DDL has no sort options`() {
        val result = canonicalizer().canonicalize(scope(brin, brin.copy(method = "btree")))
        assertEquals(2, result.candidates.map { it.id }.distinct().size)
        val candidate = result.candidates.single { it.definition.method == "brin" }
        val explicit = brin.copy(keys = listOf(key.copy(operatorClass = "\"pg_catalog\".\"int4_minmax_ops\"")))
        assertEquals(candidate, canonicalizer().canonicalize(scope(explicit)).candidates.single())
        assertEquals("CREATE INDEX \"ib_${candidate.id.take(60)}\" ON \"odd schema\".\"events\" USING brin " +
            "(\"at\"\"time\" \"pg_catalog\".\"int4_minmax_ops\");", candidate.ddl)
        assertFalse(candidate.ddl!!.contains("ASC"))
        assertThrows(AnalysisException::class.java) {
            canonicalizer().canonicalize(brin.copy(keys = listOf(key.copy(operatorClass = "pg_catalog.int4_ops"))))
        }
        for (bad in listOf(brin.copy(keys = listOf(key.copy(direction = SortDirection.DESC))),
            brin.copy(keys = listOf(key.copy(nulls = NullsOrder.FIRST))),
            brin.copy(keys = listOf(key, IndexKey(ColumnRef(table, "other")))))) {
            assertThrows(IllegalArgumentException::class.java) { canonicalizer().canonicalize(bad) }
        }
    }

    @Test
    fun `missing BRIN opclass is diagnosed without discarding usable B-tree`() {
        val result = canonicalizer(columns = columns.map { it.copy(defaultOperatorClasses = emptyMap()) })
            .canonicalize(scope(brin, brin.copy(method = "btree")))
        assertEquals("btree", result.candidates.single().definition.method)
        assertEquals(CandidateReason.NO_BRIN_OPERATOR_CLASS, result.decisions.single().reason)
        assertEquals("brin", result.decisions.single().details["method"])
        assertEquals(1, result.unindexableVariants)
    }

    @Test
    fun `only exact default BRIN duplicates are excluded`() {
        val resolved = canonicalizer().canonicalize(brin)
        val existing = ExistingIndex("real_brin", table, "brin", resolved.keys)
        assertTrue(canonicalizer(listOf(existing)).canonicalize(scope(brin)).candidates.isEmpty())
        for (different in listOf(existing.copy(method = "btree"), existing.copy(defaultStorageParameters = false),
            existing.copy(valid = false), existing.copy(predicate = "true"),
            existing.copy(keys = listOf(resolved.keys.single().copy(operatorClass = "pg_catalog.int4_minmax_multi_ops"))))) {
            assertEquals(1, canonicalizer(listOf(different)).canonicalize(scope(brin)).candidates.size)
        }
    }

    @Test
    fun `only scalar WHERE ranges generate BRIN and keep original branch provenance`() {
        for (operator in ComparisonOperator.entries) {
            for (source in PredicateSource.entries) {
                for (right in listOf(null, ColumnRef(table, "other"))) {
                    val query = ResolvedQuery("q", "unchanged", setOf(table), setOfNotNull(column, right),
                        List(2) { ResolvedPredicate(column, operator, source, right) }, emptyList(),
                        condition = ResolvedCondition.Or(ResolvedCondition.Predicate(0), ResolvedCondition.Predicate(1)))
                    val input = WorkloadInput(listOf(QueryInput("q", query.sql)), listOf(Profile("p", 1.0, mapOf("q" to 1.0))),
                        Constraints(Long.MAX_VALUE, null), indexMethods = setOf(IndexMethod.BRIN))
                    val scopes = mutableListOf<CandidateScope>()
                    BtreeCandidateEnumerator().forEachScope(ResolvedWorkload(input, listOf(query)), RunContext(), scopes::add)
                    val candidates = scopes.flatMap { it.variants }
                    val applicable = source == PredicateSource.WHERE && right == null && operator in setOf(
                        ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_THAN_OR_EQUAL, ComparisonOperator.GREATER_THAN,
                        ComparisonOperator.GREATER_THAN_OR_EQUAL, ComparisonOperator.BETWEEN)
                    assertEquals(if (applicable) 1 else 0, candidates.size, "$operator $source $right")
                    if (applicable) {
                        assertEquals(setOf(GenerationReason.BRIN_RANGE), candidates.single().generationReasons)
                        assertEquals(setOf("q"), candidates.single().sourceQueryIds)
                        assertTrue(candidates.single().sourceBranches.isNotEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun `mixed methods share column local shortlist and reserve caps`() {
        val refs = (1..100).map { ColumnRef(table, "c$it") }
        val query = ResolvedQuery("q", "unchanged", setOf(table), refs.toSet(), refs.map {
            ResolvedPredicate(it, ComparisonOperator.GREATER_THAN, PredicateSource.WHERE)
        }, emptyList())
        val input = WorkloadInput(listOf(QueryInput("q", query.sql)), listOf(Profile("p", 1.0, mapOf("q" to 1.0))),
            Constraints(Long.MAX_VALUE, null), indexMethods = IndexMethod.entries.toSet())
        val catalog = CatalogSnapshot(listOf(CatalogTable(table, refs.map {
            CatalogColumn(it.name, "int4", defaultOperatorClass = "pg_catalog.int4_ops",
                defaultOperatorClasses = mapOf("brin" to "pg_catalog.int4_minmax_ops"))
        })))
        val result = ProfileAwareCandidateGenerator().generate(ResolvedWorkload(input, listOf(query)), catalog, RunContext())
        val stats = result.diagnostics.localScopes.single()
        assertEquals(100, stats.discoveredColumns)
        assertEquals(16, stats.retainedColumns)
        assertEquals(32, stats.enumeratedDefinitions)
        assertEquals(32, stats.retainedDefinitions)
        assertEquals(16, result.candidates.size)
        assertEquals(16, result.reserve.size)
        assertEquals(setOf("btree", "brin"), (result.candidates + result.reserve).map { it.definition.method }.toSet())
        assertEquals("unchanged", query.sql)
    }
}
