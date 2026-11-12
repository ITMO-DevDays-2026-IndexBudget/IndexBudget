package ru.itmo.indexbudget.candidates

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*

class HashCandidatesTest {
    private val table = TableRef("odd schema", "events")
    private val column = ColumnRef(table, "a\"key")
    private val hash = IndexDefinition(table, listOf(IndexKey(column)), method = "hash")
    private fun catalog(existing: List<ExistingIndex> = emptyList(), hasOpclass: Boolean = true) =
        CatalogSnapshot(listOf(CatalogTable(table, listOf(CatalogColumn(column.name, "int4",
            defaultOperatorClass = "pg_catalog.int4_ops",
            defaultOperatorClasses = if (hasOpclass) mapOf("hash" to "pg_catalog.int4_ops") else emptyMap())),
            existingIndexes = existing)))
    private fun scope(vararg definitions: IndexDefinition) = CandidateScope("q", table, 1, listOf(column), emptyList(),
        definitions.map { CandidateVariant(it, setOf("q"), setOf(GenerationReason.HASH_EQUALITY)) }, emptyList(), emptyList())

    @Test
    fun `hash uses shared canonical ID DDL shape validation and conservative duplicate rules`() {
        val canonicalizer = BtreeCandidateCanonicalizer(catalog(), RunContext())
        val candidates = canonicalizer.canonicalize(scope(hash, hash.copy(method = "btree"))).candidates
        assertEquals(2, candidates.map { it.id }.distinct().size)
        val candidate = candidates.single { it.definition.method == "hash" }
        assertEquals("CREATE INDEX \"ib_${candidate.id.take(60)}\" ON \"odd schema\".\"events\" USING hash " +
            "(\"a\"\"key\" \"pg_catalog\".\"int4_ops\");", candidate.ddl)
        assertEquals(candidate.definition, canonicalizer.canonicalize(hash))
        for (bad in listOf(hash.copy(includedColumns = listOf(ColumnRef(table, "b"))), hash.copy(keys = hash.keys + IndexKey(ColumnRef(table, "b"))),
            hash.copy(keys = listOf(IndexKey(column, SortDirection.DESC))),
            hash.copy(keys = listOf(IndexKey(column, nulls = NullsOrder.FIRST))))) {
            assertThrows(IllegalArgumentException::class.java) { canonicalizer.canonicalize(bad) }
        }
        assertThrows(AnalysisException::class.java) {
            canonicalizer.canonicalize(hash.copy(keys = listOf(IndexKey(column, operatorClass = "pg_catalog.text_ops"))))
        }
        val real = ExistingIndex("real_hash", table, "hash", candidate.definition.keys)
        assertTrue(BtreeCandidateCanonicalizer(catalog(listOf(real)), RunContext()).canonicalize(scope(hash)).candidates.isEmpty())
        for (different in listOf(real.copy(method = "btree"), real.copy(defaultStorageParameters = false),
            real.copy(valid = false), real.copy(predicate = "true"), real.copy(ready = false))) {
            assertEquals(1, BtreeCandidateCanonicalizer(catalog(listOf(different)), RunContext()).canonicalize(scope(hash)).candidates.size)
        }
        val missing = BtreeCandidateCanonicalizer(catalog(hasOpclass = false), RunContext())
            .canonicalize(scope(hash, hash.copy(method = "btree")))
        assertEquals("btree", missing.candidates.single().definition.method)
        assertEquals(CandidateReason.NO_HASH_OPERATOR_CLASS, missing.decisions.single().reason)
    }

    @Test
    fun `only simple scalar WHERE equality generates hash with exact branch provenance`() {
        for (operator in ComparisonOperator.entries) for (source in PredicateSource.entries)
            for (right in listOf(null, ColumnRef(table, "b"))) {
                val query = ResolvedQuery("q", "unchanged", setOf(table), setOfNotNull(column, right), listOf(
                    ResolvedPredicate(column, operator, source, right),
                    ResolvedPredicate(column, ComparisonOperator.GREATER_THAN, PredicateSource.WHERE)),
                    listOf(OrderByColumn(column)), projectedColumns = setOf(column),
                    condition = ResolvedCondition.Or(ResolvedCondition.Predicate(0), ResolvedCondition.Predicate(1)))
                val input = WorkloadInput(listOf(QueryInput("q", query.sql)), listOf(Profile("p", 1.0, mapOf("q" to 1.0))),
                    Constraints(Long.MAX_VALUE, null), indexMethods = setOf(IndexMethod.HASH))
                val scopes = mutableListOf<CandidateScope>()
                BtreeCandidateEnumerator().forEachScope(ResolvedWorkload(input, listOf(query)), RunContext(), scopes::add)
                val candidates = scopes.flatMap { it.variants }
                val applicable = operator == ComparisonOperator.EQUAL && source == PredicateSource.WHERE && right == null
                assertEquals(if (applicable) 1 else 0, candidates.size, "$operator $source $right")
                if (applicable) {
                    assertEquals(hash, candidates.single().definition)
                    assertEquals(setOf(GenerationReason.HASH_EQUALITY), candidates.single().generationReasons)
                    assertEquals(setOf(CandidateSourceBranch("q", "condition/L")), candidates.single().sourceBranches)
                }
            }
    }

    @Test
    fun `all methods share caps before pairs and leave SQL intact`() {
        val refs = (1..100).map { ColumnRef(table, "c$it") }
        val query = ResolvedQuery("q", "unchanged", setOf(table), refs.toSet(), refs.flatMap {
            listOf(ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE),
                ResolvedPredicate(it, ComparisonOperator.GREATER_THAN, PredicateSource.WHERE))
        }, emptyList())
        val input = WorkloadInput(listOf(QueryInput("q", query.sql)), listOf(Profile("p", 1.0, mapOf("q" to 1.0))),
            Constraints(Long.MAX_VALUE, null), indexMethods = IndexMethod.entries.toSet())
        val snapshot = CatalogSnapshot(listOf(CatalogTable(table, refs.map { CatalogColumn(it.name, "int4",
            defaultOperatorClass = "pg_catalog.int4_ops", defaultOperatorClasses = mapOf(
                "brin" to "pg_catalog.int4_minmax_ops", "hash" to "pg_catalog.int4_ops")) })))
        val workload = ResolvedWorkload(input, listOf(query))
        val scopes = mutableListOf<CandidateScope>()
        BtreeCandidateEnumerator().forEachScope(workload, RunContext(), scopes::add)
        val variants = scopes.single().variants
        assertEquals(288, variants.size)
        assertEquals(mapOf("btree" to 256, "brin" to 16, "hash" to 16),
            variants.groupingBy { it.definition.method }.eachCount())
        assertEquals(16, variants.count { it.definition.method == "btree" && it.definition.keys.size == 1 })
        assertEquals(240, variants.count { it.definition.keys.size == 2 })
        for (method in listOf("brin", "hash")) {
            assertEquals(scopes.single().retainedColumns.toSet(), variants.filter { it.definition.method == method }
                .map { it.definition.keys.single().column }.toSet())
        }
        val result = ProfileAwareCandidateGenerator().generate(workload, snapshot, RunContext())
        val stats = result.diagnostics.localScopes.single()
        assertEquals(100, stats.discoveredColumns)
        assertEquals(16, stats.retainedColumns)
        assertEquals(288, stats.enumeratedDefinitions)
        assertEquals(32, stats.retainedDefinitions)
        assertEquals(16, result.candidates.size)
        assertTrue(result.reserve.size <= 48)
        assertEquals("unchanged", query.sql)
        assertTrue((result.candidates + result.reserve).all { it.estimatedBytes == null })
    }
}
