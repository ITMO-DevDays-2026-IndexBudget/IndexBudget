package ru.itmo.indexbudget.candidates

import java.time.Duration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.CandidateGenerationFixtures

class BtreeCandidateCanonicalizerTest {
    private val table = TableRef("public", "records")
    private val a = IndexKey(ColumnRef(table, "a"))
    private val b = IndexKey(ColumnRef(table, "b"))
    private val definition = IndexDefinition(table, listOf(a, b))
    private val columns = listOf("a", "b").map {
        CatalogColumn(it, "text", collation = "pg_catalog.\"default\"", defaultOperatorClass = "pg_catalog.text_ops")
    }

    @Test
    fun `implicit and explicitly quoted defaults merge before the local cap`() {
        val canonicalizer = canonicalizer()
        val explicit = definition.copy(keys = definition.keys.map {
            it.copy(collation = "\"pg_catalog\".\"default\"", operatorClass = "\"pg_catalog\".\"text_ops\"")
        })
        val variants = List(40) { variant(definition, reason = GenerationReason.TWO_EQUALITIES) } +
            variant(explicit, reason = GenerationReason.ORDER_BY_PAIR)
        val result = canonicalizer.canonicalize(scope(variants))
        assertEquals(1, result.deduplicatedDefinitions)
        val candidate = result.candidates.single()
        assertEquals(setOf("q"), candidate.sourceQueryIds)
        assertEquals(setOf(GenerationReason.ORDER_BY_PAIR, GenerationReason.TWO_EQUALITIES), candidate.generationReasons)
        assertEquals(0, candidate.bestPairPriority())
        assertEquals(explicit, candidate.definition)
        assertTrue(candidate.id.matches(Regex("[0-9a-f]{64}")))
        assertEquals(PostgresDdlRenderer().render(explicit), candidate.ddl)
        assertNull(candidate.estimatedBytes)
        assertNull(candidate.budgetedBytes)
        assertNull(candidate.writeUnitsPerHour)
    }

    @Test
    fun `global merge preserves different structures provenance and sorted order`() {
        val canonicalizer = canonicalizer()
        fun local(query: String, reason: GenerationReason, definition: IndexDefinition = this.definition) = canonicalizer.canonicalize(
            scope(listOf(variant(definition, query, reason)), query),
        ).candidates.single()
        val pair = local("z", GenerationReason.TWO_EQUALITIES)
        val single = local("single", GenerationReason.SINGLE_COLUMN, definition.copy(keys = listOf(a)))
        val reversedPair = local("reverse", GenerationReason.ORDER_BY_PAIR, definition.copy(keys = listOf(b, a)))
        val locals = listOf(pair, single, local("a", GenerationReason.EQUALITY_AND_ORDER), reversedPair,
            local("a", GenerationReason.EQUALITY_AND_RANGE)).sortedByDescending { it.id }
        val expectedPair = pair.copy(
            sourceQueryIds = setOf("a", "z"),
            generationReasons = setOf(GenerationReason.TWO_EQUALITIES, GenerationReason.EQUALITY_AND_ORDER,
                GenerationReason.EQUALITY_AND_RANGE),
        )
        val expected = setOf(expectedPair, single, reversedPair)
        for (input in listOf(locals, locals.reversed(), locals.drop(2) + locals.take(2), locals + locals)) {
            val merged = canonicalizer.merge(input)
            assertEquals(3, merged.size)
            assertEquals(expected, merged.toSet())
            assertEquals(expected.map { it.id }.sorted(), merged.map { it.id })
            val mergedPair = merged.single { it.id == pair.id }
            assertEquals(listOf("a", "z"), mergedPair.sourceQueryIds.toList())
            assertEquals(1, mergedPair.bestPairPriority())
            assertNull(merged.single { it.id == single.id }.bestPairPriority())
            assertEquals(0, merged.single { it.id == reversedPair.id }.bestPairPriority())
        }
    }

    @TestFactory
    fun `all identity dimensions distinguish candidates`() = listOf(
        "key order" to definition.copy(keys = listOf(b, a)),
        "key count" to definition.copy(keys = listOf(a)),
        "direction" to definition.copy(keys = listOf(a.copy(direction = SortDirection.DESC), b)),
        "nulls" to definition.copy(keys = listOf(a.copy(nulls = NullsOrder.FIRST), b)),
        "collation" to definition.copy(keys = listOf(a.copy(collation = "pg_catalog.\"C\""), b)),
        "opclass" to definition.copy(keys = listOf(a.copy(operatorClass = "pg_catalog.text_pattern_ops"), b)),
    ).map { (label, different) -> dynamicTest(label) {
        val result = canonicalizer().canonicalize(scope(listOf(variant(definition), variant(different))))
        assertEquals(2, result.candidates.map { it.id }.distinct().size)
        assertEquals(2, result.candidates.map { it.ddl }.distinct().size)
    } }

    @Test
    fun `schema table boundaries and identifier case participate in identity`() {
        val tables = listOf(TableRef("a.b", "c"), TableRef("a", "b.c"), TableRef("a", "B.c"))
        val canonicalizer = BtreeCandidateCanonicalizer(CatalogSnapshot(tables.map { CatalogTable(it, columns) }), RunContext())
        val ids = tables.map { ref ->
            val variant = variant(IndexDefinition(ref, listOf(IndexKey(ColumnRef(ref, "a")))))
            canonicalizer.canonicalize(scope(listOf(variant)).copy(table = ref)).candidates.single().id
        }
        assertEquals(3, ids.distinct().size)
    }

    @Test
    fun `catalog order unused columns and real index names do not change candidate identity`() {
        val first = canonicalizer().canonicalize(scope(listOf(variant(definition))))
        val changed = BtreeCandidateCanonicalizer(CatalogSnapshot(listOf(CatalogTable(table,
            columns.reversed() + CatalogColumn("unused", "json"),
            existingIndexes = listOf(existing().copy(name = "different", predicate = "a IS NOT NULL")),
        ))), RunContext()).canonicalize(scope(listOf(variant(definition))))
        assertEquals(first, changed)
    }

    @Test
    fun `exact real duplicates include unique indexes and preserve diagnostics`() {
        val real = existing()
        val canonicalizer = canonicalizer(listOf(real.copy(name = "z", unique = true), real.copy(name = "a")))
        val decision = CandidateDecision(CandidateReason.COLUMN_SHORTLIST_TRUNCATED, queryId = "q", table = table)
        val result = canonicalizer.canonicalize(scope(listOf(variant(definition))).copy(decisions = listOf(decision)))
        assertTrue(result.candidates.isEmpty())
        assertEquals(1, result.deduplicatedDefinitions)
        assertEquals(decision, result.decisions.first())
        val duplicates = result.decisions.drop(1)
        assertEquals(listOf("a", "z"), duplicates.map { it.details.getValue("existing_index") })
        assertTrue(duplicates.all { it.reason == CandidateReason.EXACT_EXISTING_DUPLICATE && it.queryId == "q" &&
            it.table == table && it.phase == AnalysisPhase.CANDIDATES && it.candidateId != null })
    }

    @TestFactory
    fun `nonmatching and nonordinary existing indexes never remove candidates`(): List<org.junit.jupiter.api.DynamicTest> {
        val exact = existing()
        val keys = exact.keys
        return listOf(
            "invalid" to exact.copy(valid = false),
            "not ready" to exact.copy(ready = false),
            "inaccessible" to exact.copy(accessible = false),
            "partial" to exact.copy(predicate = "a IS NOT NULL"),
            "expression" to exact.copy(expressions = listOf("lower(a)")),
            "include" to exact.copy(includedColumns = listOf(a.column)),
            "other method" to exact.copy(method = "hash"),
            "other table" to exact.copy(table = TableRef("other", "records")),
            "reverse keys" to exact.copy(keys = keys.reversed()),
            "prefix" to exact.copy(keys = keys.take(1)),
            "direction" to exact.copy(keys = listOf(keys[0].copy(direction = SortDirection.DESC), keys[1])),
            "nulls" to exact.copy(keys = listOf(keys[0].copy(nulls = NullsOrder.FIRST), keys[1])),
            "collation" to exact.copy(keys = listOf(keys[0].copy(collation = "pg_catalog.\"C\""), keys[1])),
            "opclass" to exact.copy(keys = listOf(keys[0].copy(operatorClass = "pg_catalog.text_pattern_ops"), keys[1])),
            "missing opclass" to exact.copy(keys = listOf(keys[0].copy(operatorClass = null), keys[1])),
            "missing collation" to exact.copy(keys = listOf(keys[0].copy(collation = null), keys[1])),
        ).map { (label, index) -> dynamicTest(label) {
            val result = canonicalizer(listOf(index)).canonicalize(scope(listOf(variant(definition))))
            assertEquals(1, result.candidates.size)
            assertTrue(result.decisions.isEmpty())
        } }
    }

    @Test
    fun `pair and partial single do not cover a single candidate`() {
        val real = existing()
        val indexes = listOf(real, real.copy(keys = real.keys.take(1), predicate = "a IS NOT NULL"))
        val result = canonicalizer(indexes).canonicalize(scope(listOf(variant(definition.copy(keys = listOf(a))))))
        assertEquals(1, result.candidates.size)
        assertTrue(result.decisions.isEmpty())
        assertNull(result.candidates.single().bestPairPriority())
    }

    @Test
    fun `unknown defaults and missing catalog facts fail explicitly`() {
        val catalogs = listOf(CatalogSnapshot(emptyList()), CatalogSnapshot(listOf(CatalogTable(table, emptyList()))),
            CatalogSnapshot(listOf(CatalogTable(table, listOf(CatalogColumn("a", "json"))))))
        for (catalog in catalogs) {
            val error = assertThrows(AnalysisException::class.java) {
                BtreeCandidateCanonicalizer(catalog, RunContext()).canonicalize(definition.copy(keys = listOf(a)))
            }
            assertEquals(ErrorCode.CATALOG_ERROR, error.error.code)
        }
    }

    @Test
    fun `generation boundary retains all canonical structures before local selection`() {
        val scenario = CandidateGenerationFixtures.wideAllFilters()
        val context = RunContext()
        val canonicalizer = BtreeCandidateCanonicalizer(scenario.catalog, context)
        BtreeCandidateEnumerator().forEachScope(scenario.workload, context) { scope ->
            val result = canonicalizer.canonicalize(scope)
            assertEquals(256, result.deduplicatedDefinitions)
            assertEquals(256, result.candidates.size)
            assertEquals(scope.decisions, result.decisions)
            assertEquals(100, scope.discoveredColumns)
            assertEquals(84, scope.excludedColumns.size)
            assertEquals(result, canonicalizer.canonicalize(scope.copy(variants = scope.variants.reversed())))
        }
        assertEquals(0, context.statistics().explainAttempts)
    }

    @Test
    fun `deadline interrupts merge without returning partial output`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(10)), clock = NanoClock { now })
        val canonicalizer = canonicalizer(context = context)
        val candidate = canonicalizer.canonicalize(scope(listOf(variant(definition)))).candidates.single()
        val delayed = Iterable {
            sequence {
                yield(candidate)
                now = 10L
                yield(candidate)
            }.iterator()
        }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AnalysisException::class.java) {
            canonicalizer.merge(delayed)
        }.error.code)
    }

    @Test
    fun `deadline interrupts variant traversal before consuming the rest of the scope`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(10)), clock = NanoClock { now })
        val canonicalizer = canonicalizer(context = context)
        val visited = mutableListOf<Int>()
        val variants = object : AbstractList<CandidateVariant>() {
            override val size = 3
            override fun get(index: Int): CandidateVariant {
                visited += index
                if (index == 1) now = 10L
                return variant(definition)
            }
        }

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AnalysisException::class.java) {
            canonicalizer.canonicalize(scope(variants))
        }.error.code)
        // A check only after the loop would throw too, but would consume the third variant.
        assertEquals(listOf(0, 1), visited)
    }

    @Test
    fun `deadline interrupts existing index traversal before consuming the rest of the catalog`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(10)), clock = NanoClock { now })
        val visited = mutableListOf<Int>()
        val indexes = object : AbstractList<ExistingIndex>() {
            override val size = 3
            override fun get(index: Int): ExistingIndex {
                visited += index
                if (index == 1) now = 10L
                return existing().copy(name = "existing_$index")
            }
        }
        val canonicalizer = canonicalizer(indexes, context)
        assertTrue(visited.isEmpty())

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AnalysisException::class.java) {
            canonicalizer.canonicalize(scope(listOf(variant(definition))))
        }.error.code)
        // The final scope check cannot substitute for stopping this catalog traversal.
        assertEquals(listOf(0, 1), visited)
    }

    @Test
    fun `merge rejects estimated candidates and inconsistent identities`() {
        val canonicalizer = canonicalizer()
        val candidate = canonicalizer.canonicalize(scope(listOf(variant(definition)))).candidates.single()
        assertThrows(IllegalArgumentException::class.java) { canonicalizer.merge(listOf(candidate.copy(estimatedBytes = 0))) }
        assertThrows(IllegalArgumentException::class.java) { canonicalizer.merge(listOf(candidate.copy(id = "wrong"))) }
    }

    private fun canonicalizer(indexes: List<ExistingIndex> = emptyList(), context: RunContext = RunContext()) =
        BtreeCandidateCanonicalizer(CatalogSnapshot(listOf(CatalogTable(table, columns, existingIndexes = indexes))), context)

    private fun existing(): ExistingIndex = ExistingIndex("existing", table, "btree", definition.keys.map {
        it.copy(collation = "pg_catalog.\"default\"", operatorClass = "pg_catalog.text_ops")
    })

    private fun variant(definition: IndexDefinition, query: String = "q", reason: GenerationReason =
        if (definition.keys.size == 1) GenerationReason.SINGLE_COLUMN else GenerationReason.TWO_EQUALITIES) =
        CandidateVariant(definition, setOf(query), setOf(reason))

    private fun scope(variants: List<CandidateVariant>, query: String = "q") = CandidateScope(
        query, table, 2, listOf(a.column, b.column), emptyList(), variants, emptyList(), emptyList(),
    )
}
