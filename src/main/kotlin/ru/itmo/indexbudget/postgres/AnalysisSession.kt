package ru.itmo.indexbudget.postgres

import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.DatabaseVersions
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.TableName
import ru.itmo.indexbudget.optimizer.ConfigurationEvaluator

/**
 * Owns one physical connection for one command. It is not shared or pooled across active analyses.
 * Every evaluate resets hypotheses before creating the exact set and again in finally, with rollback
 * before reset after a failed transaction. Closing must clean up or close the connection. No JDBC type
 * crosses this port. The application owns the session lifetime and closes it in finally.
 */
interface AnalysisSession : ConfigurationEvaluator, AutoCloseable {
    /** Already captured versions, with no SQL/getConnection call; unknown values remain null. */
    val databaseVersions: DatabaseVersions get() = DatabaseVersions()

    /** Credential-free endpoint/database/user identity, hashed by the adapter; null if unavailable. */
    val sourceFingerprint: String? get() = null

    /** Optional bounded metadata refresh on this session for cross-run condition checks. */
    fun readDatabaseVersions(context: RunContext): DatabaseVersions = databaseVersions

    /** Unqualified names are resolved against this connection's PostgreSQL search path. */
    fun readCatalog(tables: Set<TableName>, context: RunContext): CatalogSnapshot

    /** Size of a candidate visited in the bounded shortlist/reserve queue, with the same cleanup/deadline contract as evaluate. */
    fun estimateSize(candidate: IndexCandidate, context: RunContext): Long

    /**
     * Idempotent cancellation, safe to call from a shutdown thread while a SQL operation is active.
     * Cancel the active statement and release/abort the physical connection. Do not start cleanup
     * SQL after the context's time budget expires. Closing afterward must remain safe.
     */
    fun cancel()

    override fun close()
}

/** Concrete implementations receive connection settings privately, outside domain objects/reports. */
interface AnalysisSessionFactory {
    fun open(context: RunContext): AnalysisSession
}
