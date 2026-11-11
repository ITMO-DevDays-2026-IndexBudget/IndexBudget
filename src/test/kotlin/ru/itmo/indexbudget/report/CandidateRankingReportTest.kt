package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.fixtures.HtmlReportFixtures
import tools.jackson.databind.json.JsonMapper

class CandidateRankingReportTest {
    @Test
    fun `builder JSON and HTML preserve effective ranking for every status without extra evaluations`() {
        for (ranking in CandidateRanking.entries) {
            for (status in RunStatus.entries) {
                val source = ContractFixtures.report(status = status)
                val input = requireNotNull(source.input).copy(candidateRanking = ranking)
                val context = RunContext()
                val before = context.statistics()

                val report = DefaultReportBuilder().build(input, source.candidates, source.result, context)
                val json = JsonMapper.builder().build().readTree(JsonReportWriter().render(report))
                val html = HtmlReportRenderer().render(report)

                assertEquals(ranking, report.candidateRanking)
                assertEquals(ranking.wireName, json["candidate_ranking"].stringValue())
                assertTrue(html.contains("Рейтинг shortlist"))
                assertTrue(html.contains(ranking.wireName))
                assertEquals(ranking == CandidateRanking.BASELINE_COST, html.contains("экспериментальный"))
                assertSame(source.result, report.result)
                assertEquals(before, context.statistics())
            }
        }
    }

    @Test
    fun `ranking is retained when failed report has no input and for zero baseline`() {
        val source = ContractFixtures.report(status = RunStatus.FAILED)
        val failed = source.copy(input = null, candidateRanking = CandidateRanking.BASELINE_COST)
        val json = JsonMapper.builder().build().readTree(JsonReportWriter().render(failed))
        assertEquals("baseline_cost", json["candidate_ranking"].stringValue())
        assertTrue(HtmlReportRenderer().render(failed).contains("baseline_cost"))

        val empty = HtmlReportFixtures.zeroBaseline().copy(candidateRanking = CandidateRanking.BASELINE_COST)
        assertTrue(HtmlReportRenderer().render(empty).contains("baseline_cost"))
        assertEquals("baseline_cost", JsonMapper.builder().build().readTree(JsonReportWriter().render(empty))
            ["candidate_ranking"].stringValue())
    }
}
