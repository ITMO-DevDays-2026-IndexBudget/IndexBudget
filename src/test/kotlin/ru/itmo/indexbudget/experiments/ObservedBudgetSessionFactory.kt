package ru.itmo.indexbudget.experiments

import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory

/** Owns the new session until initialization succeeds and the pipeline can register it. */
internal class ObservedBudgetSessionFactory(
    private val createSession: () -> AnalysisSession,
) : AnalysisSessionFactory {
    override fun open(context: RunContext): AnalysisSession {
        val session = createSession()
        try {
            session.readDatabaseVersions(context)
            return session
        } catch (failure: Throwable) {
            try {
                session.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }
}
