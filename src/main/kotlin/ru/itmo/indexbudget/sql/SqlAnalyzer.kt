package ru.itmo.indexbudget.sql

import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.TableName
import ru.itmo.indexbudget.domain.WorkloadInput

/** Parsing implementations may retain their AST privately; the public API does not expose parser types. */
interface ParsedWorkload {
    val input: WorkloadInput
    val referencedTables: Set<TableName>
}

interface SqlAnalyzer {
    /** Reject unsupported syntax and collect table names before catalog introspection. */
    fun parse(input: WorkloadInput, context: RunContext): ParsedWorkload

    /** Resolve every reference, including projections and columns later excluded by the generator. */
    fun resolve(parsed: ParsedWorkload, catalog: CatalogSnapshot, context: RunContext): ResolvedWorkload
}
