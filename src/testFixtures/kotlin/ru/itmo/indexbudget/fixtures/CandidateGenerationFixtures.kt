package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.candidates.CandidateVariant
import ru.itmo.indexbudget.domain.*

/** Hand-specified SQL/resolution/output examples shared by #9, #10, #11 and #12. No database needed. */
data class CandidateGenerationScenario(
    val name: String,
    val catalog: CatalogSnapshot,
    val workload: ResolvedWorkload,
    val expectedScopes: List<ExpectedCandidateScope>,
)

data class ExpectedCandidateScope(
    val queryId: String,
    val table: TableRef,
    val retainedColumns: List<ColumnRef>,
    val excludedColumns: List<ColumnRef> = emptyList(),
    val enumeratedDefinitions: Int,
    /** Null only for large cases whose count/column boundary is the oracle. */
    val variants: Set<CandidateVariant>? = null,
)

object CandidateGenerationFixtures {
    val table = TableRef("public", "records")
    val otherTable = TableRef("public", "groups")

    fun all(): List<CandidateGenerationScenario> = listOf(
        equalityRange(), twoEqualities(), filterAndSort(), mixedOrder(), overlappingReasons(), join(), projectionOnly(),
        wideTwoFeatures(), wideAllFilters(), repeatedPredicates(), profileSupport(),
    )

    fun equalityRange(): CandidateGenerationScenario {
        val query = query(
            "SELECT * FROM public.records WHERE a = 1 AND b >= 2",
            predicates = listOf(predicate("a"), predicate("b", ComparisonOperator.GREATER_THAN_OR_EQUAL)),
        )
        return small("equality before range", query, listOf("a", "b"), setOf(
            single("a"), single("b"), pair("a", "b", GenerationReason.EQUALITY_AND_RANGE),
        ))
    }

    fun twoEqualities(): CandidateGenerationScenario {
        val query = query(
            "SELECT * FROM public.records WHERE a = 1 AND b = 2",
            predicates = listOf(predicate("a"), predicate("b")),
        )
        return small("both equality orders", query, listOf("a", "b"), setOf(
            single("a"), single("b"),
            pair("a", "b", GenerationReason.TWO_EQUALITIES), pair("b", "a", GenerationReason.TWO_EQUALITIES),
        ))
    }

    fun mixedOrder(): CandidateGenerationScenario {
        val query = query(
            "SELECT * FROM public.records ORDER BY a DESC, b ASC LIMIT 20",
            order = listOf(OrderByColumn(column("a"), SortDirection.DESC), OrderByColumn(column("b"))),
        )
        return small("mixed directions and default nulls", query, listOf("a", "b"), setOf(
            single("a"), single("b"),
            pair("a", "b", GenerationReason.ORDER_BY_PAIR, firstDirection = SortDirection.DESC),
        ))
    }

    fun filterAndSort(): CandidateGenerationScenario {
        val query = query(
            "SELECT * FROM public.records WHERE a = 1 ORDER BY b DESC LIMIT 20",
            predicates = listOf(predicate("a")), order = listOf(OrderByColumn(column("b"), SortDirection.DESC)),
        )
        return small("equality before descending sort", query, listOf("b", "a"), setOf(
            single("a"), single("b"),
            pair("a", "b", GenerationReason.EQUALITY_AND_ORDER, secondDirection = SortDirection.DESC),
        ))
    }

    fun overlappingReasons(): CandidateGenerationScenario {
        val query = query(
            "SELECT * FROM public.records WHERE a = 1 AND b = 2 AND b > 0 ORDER BY a DESC, b ASC",
            predicates = listOf(predicate("a"), predicate("b"), predicate("b", ComparisonOperator.GREATER_THAN)),
            order = listOf(OrderByColumn(column("a"), SortDirection.DESC), OrderByColumn(column("b"))),
        )
        val forward = pair("a", "b", GenerationReason.ORDER_BY_PAIR, firstDirection = SortDirection.DESC).copy(
            generationReasons = setOf(
                GenerationReason.ORDER_BY_PAIR, GenerationReason.EQUALITY_AND_ORDER,
                GenerationReason.EQUALITY_AND_RANGE, GenerationReason.TWO_EQUALITIES,
            ),
        )
        val backward = pair("b", "a", GenerationReason.TWO_EQUALITIES, secondDirection = SortDirection.DESC).copy(
            generationReasons = setOf(GenerationReason.EQUALITY_AND_ORDER, GenerationReason.TWO_EQUALITIES),
        )
        return small("overlapping roles merge provenance", query, listOf("a", "b"), setOf(
            single("a"), single("b"), forward, backward,
        ))
    }

    fun join(): CandidateGenerationScenario {
        val left = column("group_id")
        val right = ColumnRef(otherTable, "id")
        val query = ResolvedQuery(
            "q", "SELECT r.* FROM public.records r INNER JOIN public.groups g ON r.group_id = g.id WHERE r.a > 0",
            setOf(table, otherTable), setOf(left, right, column("a")),
            listOf(
                ResolvedPredicate(left, ComparisonOperator.EQUAL, PredicateSource.JOIN, right),
                predicate("a", ComparisonOperator.GREATER_THAN),
            ), emptyList(), selectsAllColumns = true,
        )
        val otherSingle = CandidateVariant(
            IndexDefinition(otherTable, listOf(IndexKey(right))), setOf("q"), setOf(GenerationReason.SINGLE_COLUMN),
        )
        return CandidateGenerationScenario(
            "join resolves both sides without cross-table pairs",
            CatalogSnapshot(listOf(
                CatalogTable(table, listOf(CatalogColumn("group_id", "int4"), CatalogColumn("a", "int4"))),
                CatalogTable(otherTable, listOf(CatalogColumn("id", "int4"))),
            )), workload(listOf(query)),
            listOf(
                ExpectedCandidateScope("q", otherTable, listOf(right), enumeratedDefinitions = 1, variants = setOf(otherSingle)),
                ExpectedCandidateScope("q", table, listOf(left, column("a")), enumeratedDefinitions = 3, variants = setOf(
                    single("group_id"), single("a"), pair("group_id", "a", GenerationReason.EQUALITY_AND_RANGE),
                )),
            ),
        )
    }

    fun projectionOnly(): CandidateGenerationScenario = small(
        "projection does not generate indexes",
        query("SELECT a, b FROM public.records").copy(
            referencedColumns = setOf(column("a"), column("b")),
            projectedColumns = setOf(column("a"), column("b")), selectsAllColumns = false,
        ), emptyList(), emptySet(),
    )

    fun wideTwoFeatures(): CandidateGenerationScenario {
        val catalog = ContractFixtures.wideCatalog()
        val input = ContractFixtures.wideQuery()
        val table = ContractFixtures.wideTable
        val a = ColumnRef(table, "c1")
        val b = ColumnRef(table, "c2")
        val query = ResolvedQuery(
            input.id, input.sql, setOf(table), catalog.tables.single().columns.map { ColumnRef(table, it.name) }.toSet(),
            listOf(ResolvedPredicate(a, ComparisonOperator.EQUAL, PredicateSource.WHERE)),
            listOf(OrderByColumn(b)), selectsAllColumns = true,
        )
        val variants = setOf(
            CandidateVariant(IndexDefinition(table, listOf(IndexKey(a))), setOf(input.id), setOf(GenerationReason.SINGLE_COLUMN)),
            CandidateVariant(IndexDefinition(table, listOf(IndexKey(b))), setOf(input.id), setOf(GenerationReason.SINGLE_COLUMN)),
            CandidateVariant(
                IndexDefinition(table, listOf(IndexKey(a), IndexKey(b))), setOf(input.id),
                setOf(GenerationReason.EQUALITY_AND_ORDER),
            ),
        )
        return CandidateGenerationScenario(
            "100 columns but only two features", catalog, workload(listOf(query)),
            listOf(ExpectedCandidateScope(input.id, table, listOf(b, a), enumeratedDefinitions = 3, variants = variants)),
        )
    }

    fun wideAllFilters(): CandidateGenerationScenario {
        val table = ContractFixtures.wideTable
        val columns = (1..100).map { ColumnRef(table, "c$it") }
        val query = ResolvedQuery(
            "wide", "SELECT * FROM public.wide_records WHERE " + (1..100).joinToString(" AND ") { "c$it = $it" },
            setOf(table), columns.toSet(),
            columns.map { ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE) },
            emptyList(), selectsAllColumns = true,
        )
        // Literal oracle for lexicographic physical IDs; not produced by the generator.
        val retained = listOf("c1", "c10", "c100", "c11", "c12", "c13", "c14", "c15", "c16", "c17", "c18", "c19", "c2", "c20", "c21", "c22")
            .map { ColumnRef(table, it) }
        return CandidateGenerationScenario(
            "100 filter columns are capped before pairs", ContractFixtures.wideCatalog(), workload(listOf(query)),
            listOf(ExpectedCandidateScope(
                query.id, table, retained, (columns - retained.toSet()).sortedBy { it.name }, enumeratedDefinitions = 256,
            )),
        )
    }

    fun repeatedPredicates(): CandidateGenerationScenario {
        val original = twoEqualities()
        val query = original.workload.queries.single().copy(
            sql = "SELECT * FROM public.records WHERE " + List(128) { "a = 1 AND b = 2" }.joinToString(" AND "),
            predicates = List(128) { original.workload.queries.single().predicates }.flatten(),
        )
        return original.copy(name = "256 repeated predicates enumerate each pair once", workload = workload(listOf(query)))
    }

    fun profileSupport(): CandidateGenerationScenario {
        val table = ContractFixtures.wideTable
        val columns = (1..18).map { ColumnRef(table, "c$it") }
        fun resolved(id: String, used: List<ColumnRef>) = ResolvedQuery(
            id, "SELECT * FROM public.wide_records WHERE " + used.joinToString(" AND ") { "${it.name} = 1" },
            setOf(table), used.toSet(),
            used.map { ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE) }, emptyList(),
            selectsAllColumns = true,
        )
        val queries = listOf(resolved("wide", columns), resolved("common", listOf(columns[16])), resolved("rare", listOf(columns[17])))
        val workload = workload(queries, listOf(
            Profile("normal", 0.999, mapOf("wide" to 1000.0, "common" to 9000.0, "rare" to 0.0)),
            Profile("campaign", 0.001, mapOf("wide" to 0.0, "common" to 0.0, "rare" to 1.0)),
        ))
        fun singleScope(queryId: String, column: ColumnRef) = ExpectedCandidateScope(
            queryId, table, listOf(column), enumeratedDefinitions = 1,
            variants = setOf(CandidateVariant(
                IndexDefinition(table, listOf(IndexKey(column))), setOf(queryId), setOf(GenerationReason.SINGLE_COLUMN),
            )),
        )
        val retained = listOf("c17", "c18", "c1", "c10", "c11", "c12", "c13", "c14", "c15", "c16", "c2", "c3", "c4", "c5", "c6", "c7")
            .map { ColumnRef(table, it) }
        return CandidateGenerationScenario(
            "rare profile gets column support independently of traffic and weight", ContractFixtures.wideCatalog(), workload,
            listOf(
                singleScope("common", columns[16]), singleScope("rare", columns[17]),
                ExpectedCandidateScope("wide", table, retained, listOf(columns[7], columns[8]), enumeratedDefinitions = 256),
            ),
        )
    }

    /** Build coherent input and resolved queries; custom tests may supply additional profiles. */
    fun workload(
        queries: List<ResolvedQuery>,
        profiles: List<Profile> = listOf(Profile("default", 1.0, queries.associate { it.id to 1.0 })),
    ): ResolvedWorkload = ResolvedWorkload(
        WorkloadInput(queries.map { QueryInput(it.id, it.sql) }, profiles, Constraints(67_108_864L, null)), queries,
    )

    private fun column(name: String) = ColumnRef(table, name)
    private fun predicate(name: String, operator: ComparisonOperator = ComparisonOperator.EQUAL) =
        ResolvedPredicate(column(name), operator, PredicateSource.WHERE)

    private fun query(
        sql: String,
        predicates: List<ResolvedPredicate> = emptyList(),
        order: List<OrderByColumn> = emptyList(),
    ) = ResolvedQuery(
        "q", sql, setOf(table),
        (predicates.flatMap { listOfNotNull(it.leftColumn, it.rightColumn) } + order.map { it.column }).toSet(),
        predicates, order, selectsAllColumns = true,
    )

    private fun small(
        name: String,
        query: ResolvedQuery,
        retained: List<String>,
        variants: Set<CandidateVariant>,
    ) = CandidateGenerationScenario(
        name, CatalogSnapshot(listOf(CatalogTable(table, listOf("a", "b", "unused").map { CatalogColumn(it, "int4") }))),
        workload(listOf(query)),
        listOf(ExpectedCandidateScope("q", table, retained.map(::column), enumeratedDefinitions = variants.size, variants = variants)),
    )

    private fun single(name: String) = CandidateVariant(
        IndexDefinition(table, listOf(IndexKey(column(name)))), setOf("q"), setOf(GenerationReason.SINGLE_COLUMN),
    )

    private fun pair(
        first: String,
        second: String,
        reason: GenerationReason,
        firstDirection: SortDirection = SortDirection.ASC,
        secondDirection: SortDirection = SortDirection.ASC,
    ) = CandidateVariant(
        IndexDefinition(table, listOf(IndexKey(column(first), firstDirection), IndexKey(column(second), secondDirection))),
        setOf("q"), setOf(reason),
    )
}
