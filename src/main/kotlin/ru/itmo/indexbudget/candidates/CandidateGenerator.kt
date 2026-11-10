package ru.itmo.indexbudget.candidates

import ru.itmo.indexbudget.domain.CandidateBatch
import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.IndexDefinition
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext

interface CandidateGenerator {
    /** Apply column/local/global limits before requesting any HypoPG size estimates. */
    fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext): CandidateBatch
}

/** Shared by HypoPG and SQL export. Input must have resolved catalog defaults; never executes SQL. */
interface DdlRenderer {
    fun render(definition: IndexDefinition): String
}
