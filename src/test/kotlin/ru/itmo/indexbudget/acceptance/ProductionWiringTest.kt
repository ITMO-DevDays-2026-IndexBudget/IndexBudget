package ru.itmo.indexbudget.acceptance

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.itmo.indexbudget.IndexBudgetApplication
import ru.itmo.indexbudget.application.AnalysisPipeline
import ru.itmo.indexbudget.application.AnalysisRunner
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator

class ProductionWiringTest {
    @Test
    fun `installed application has every production analysis port without opening a database`() {
        ApplicationContextRunner().withUserConfiguration(IndexBudgetApplication::class.java).run { context ->
            assertInstanceOf(ProfileAwareCandidateGenerator::class.java, context.getBean(CandidateGenerator::class.java))
            assertInstanceOf(AnalysisPipeline::class.java, context.getBean(AnalysisRunner::class.java))
        }
    }
}
