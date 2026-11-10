package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.itmo.indexbudget.domain.AnalysisReport
import ru.itmo.indexbudget.domain.MetricsCalculator
import ru.itmo.indexbudget.domain.MetricsCalculatorImpl
import ru.itmo.indexbudget.report.AtomicReportExporter
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.ReportBuilder
import ru.itmo.indexbudget.report.ReportExporter
import ru.itmo.indexbudget.report.ReportRenderer

class ReportConfigurationTest {
    private val context = ApplicationContextRunner()
        .withBean(MetricsCalculator::class.java, { MetricsCalculatorImpl() })
        .withUserConfiguration(ReportConfiguration::class.java)

    @Test
    fun `missing HTML port leaves exporter explicitly unavailable`() {
        context.run {
            assertInstanceOf(DefaultReportBuilder::class.java, it.getBean(ReportBuilder::class.java))
            assertFalse(it.containsBean("reportExporter"))
        }
    }

    @Test
    fun `available HTML port enables production export`() {
        context.withBean(ReportRenderer::class.java, { object : ReportRenderer {
            override fun render(report: AnalysisReport) = "<html></html>"
        } }).run {
            assertInstanceOf(AtomicReportExporter::class.java, it.getBean(ReportExporter::class.java))
        }
    }
}
