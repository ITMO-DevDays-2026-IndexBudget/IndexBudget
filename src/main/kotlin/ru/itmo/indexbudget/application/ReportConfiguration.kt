package ru.itmo.indexbudget.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.itmo.indexbudget.domain.MetricsCalculator
import ru.itmo.indexbudget.report.AtomicReportExporter
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.ReportBuilder
import ru.itmo.indexbudget.report.ReportExporter
import ru.itmo.indexbudget.report.ReportRenderer

/** HTML is supplied by #20; an absent renderer must never silently produce an incomplete run. */
@Configuration(proxyBeanMethods = false)
class ReportConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReportBuilder::class)
    fun reportBuilder(metrics: MetricsCalculator): ReportBuilder = DefaultReportBuilder(metrics)

    @Bean
    @ConditionalOnBean(ReportRenderer::class)
    @ConditionalOnMissingBean(ReportExporter::class)
    fun reportExporter(renderer: ReportRenderer): ReportExporter = AtomicReportExporter(renderer)
}
