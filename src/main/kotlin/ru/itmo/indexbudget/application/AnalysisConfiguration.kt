package ru.itmo.indexbudget.application

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.domain.MetricsCalculator
import ru.itmo.indexbudget.domain.MetricsCalculatorImpl
import ru.itmo.indexbudget.domain.ObjectiveComparator
import ru.itmo.indexbudget.domain.ObjectiveComparatorImpl
import ru.itmo.indexbudget.optimizer.GreedyOptimizer
import ru.itmo.indexbudget.optimizer.Optimizer
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.report.ReportBuilder
import ru.itmo.indexbudget.report.ReportExporter
import ru.itmo.indexbudget.sql.SqlAnalyzer

/** All production ports must be present; fixtures are never registered in the CLI. */
@Configuration(proxyBeanMethods = false)
class AnalysisConfiguration {
    @Bean
    fun metricsCalculator(): MetricsCalculator = MetricsCalculatorImpl()

    @Bean
    fun objectiveComparator(): ObjectiveComparator = ObjectiveComparatorImpl()

    /** PoC strategy; fixed-S0 repair and its complete stop handling are added in #18. */
    @Bean
    fun optimizer(): Optimizer = GreedyOptimizer()

    @Bean
    fun analysisRunner(
        sql: ObjectProvider<SqlAnalyzer>,
        sessions: ObjectProvider<AnalysisSessionFactory>,
        candidates: ObjectProvider<CandidateGenerator>,
        metrics: ObjectProvider<MetricsCalculator>,
        comparator: ObjectProvider<ObjectiveComparator>,
        optimizer: ObjectProvider<Optimizer>,
        reports: ObjectProvider<ReportBuilder>,
        exporter: ObjectProvider<ReportExporter>,
    ): AnalysisRunner {
        val sqlPort = sql.ifAvailable
        val sessionPort = sessions.ifAvailable
        val candidatePort = candidates.ifAvailable
        val metricsPort = metrics.ifAvailable
        val comparatorPort = comparator.ifAvailable
        val optimizerPort = optimizer.ifAvailable
        val reportPort = reports.ifAvailable
        val exportPort = exporter.ifAvailable
        val missing = listOf(
            "SqlAnalyzer" to sqlPort,
            "AnalysisSessionFactory" to sessionPort,
            "CandidateGenerator" to candidatePort,
            "MetricsCalculator" to metricsPort,
            "ObjectiveComparator" to comparatorPort,
            "Optimizer" to optimizerPort,
            "ReportBuilder" to reportPort,
            "ReportExporter" to exportPort,
        ).filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) return UnavailableAnalysisRunner(missing)
        return AnalysisPipeline(
            sqlAnalyzer = checkNotNull(sqlPort),
            sessionFactory = checkNotNull(sessionPort),
            candidateGenerator = checkNotNull(candidatePort),
            metrics = checkNotNull(metricsPort),
            comparator = checkNotNull(comparatorPort),
            optimizer = checkNotNull(optimizerPort),
            reportBuilder = checkNotNull(reportPort),
            reportExporter = checkNotNull(exportPort),
        )
    }
}
