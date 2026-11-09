package ru.itmo.indexbudget.postgres

import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.TableName
import ru.itmo.indexbudget.optimizer.ConfigurationEvaluator

/**
 * Owns one physical connection for one command. It is not shared or pooled across active analyses.
 * Every evaluate resets hypotheses before creating the exact set and again in finally, with rollback
 * before reset after a failed transaction. Closing must clean up or close the connection. No JDBC type
 * crosses this port. The application owns the session lifetime and closes it with `use`.
 */
interface AnalysisSession : ConfigurationEvaluator, AutoCloseable {
    /** Unqualified names are resolved against this connection's PostgreSQL search path. */
    fun readCatalog(tables: Set<TableName>, context: RunContext): CatalogSnapshot

    /** Size of a shortlisted hypothetical index, with the same cleanup/deadline contract as evaluate. */
    fun estimateSize(candidate: IndexCandidate, context: RunContext): Long

    override fun close()
}

/** Concrete implementations receive connection settings privately, outside domain objects/reports. */
interface AnalysisSessionFactory {
    fun open(context: RunContext): AnalysisSession
}
