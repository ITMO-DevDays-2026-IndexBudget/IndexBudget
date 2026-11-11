package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import tools.jackson.databind.json.JsonMapper

class JsonReportWriterTest {
    private val mapper = JsonMapper.builder().build()
    private val writer = JsonReportWriter()

    @Test
    fun `wire v3 exposes both incumbents from one pool and embeds saved plan JSON`() {
        val report = ContractFixtures.report(objective = Objective.MEAN)
        val json = mapper.readTree(writer.render(report))

        assertEquals(3, json["schema_version"].intValue())
        assertEquals("0.2", json["spec_version"].stringValue())
        assertEquals(report.runId, json["run_id"].stringValue())
        assertEquals(report.inputHash, json["input_hash"].stringValue())
        assertEquals("completed", json["status"].stringValue())
        assertEquals("mean", json["selected_objective"].stringValue())
        assertEquals("evaluated_sets", json["comparison_scope"].stringValue())
        assertFalse(json["incumbents_coincide"].booleanValue())
        assertEquals(88_900.0, json["price_of_robustness"].doubleValue())
        assertEquals(json["best_mean"], json["selected"])
        assertEquals("records_group_created_at", json["selected_indexes"][0]["id"].stringValue())
        assertEquals("records_created_at", json["best_robust"]["candidate_ids"][0].stringValue())
        val plans = json["best_mean"]["plans_by_query_id"]
        assertEquals(mapper.readTree(requireNotNull(report.result.bestMean).evaluation
            .plansByQueryId.getValue("records_by_group").json), plans["records_by_group"])
        assertEquals(1.2, json["selected"]["metrics"]["budget_usage"]["size_safety_factor"].doubleValue())
        assertEquals(0.9, json["profiles"][0]["normalized_weight"].doubleValue())
        assertNull(json["result"])
        assertNull(json["schemaVersion"])
    }

    @Test
    fun `unknown values and exclusions are represented without fabricated measurements`() {
        val report = ContractFixtures.report(sameIncumbents = true, withWriteProfile = false)
        val excluded = ContractFixtures.unestimatedCandidate()
        val withExclusion = report.copy(candidates = report.candidates.copy(
            candidates = report.candidates.candidates + excluded,
            diagnostics = report.candidates.diagnostics.copy(
                decisions = listOf(CandidateDecision(CandidateReason.GLOBAL_CANDIDATE_LIMIT, excluded.id)),
            ),
        ))
        val json = mapper.readTree(writer.render(withExclusion))

        assertTrue(json["table_write_rates"].isNull)
        assertTrue(json["budgets"]["write_budget_units_per_hour"].isNull)
        assertTrue(json["selected"]["metrics"]["budget_usage"]["write_units_per_hour"].isNull)
        assertTrue(json["candidates"][1]["estimated_bytes"].isNull)
        assertTrue(json["candidates"][1]["budgeted_bytes"].isNull)
        assertTrue(json["candidates"][1]["write_units_per_hour"].isNull)
        assertNull(json["candidates"][1]["cost"])
        assertTrue(json["candidate_diagnostics"]["ungenerated_definitions"].isNull)
        assertTrue(json["candidate_diagnostics"]["preparation"].isNull)
        assertTrue(json["diagnostics"]["unexamined_configurations"].isNull)
        assertEquals("GLOBAL_CANDIDATE_LIMIT", json["candidate_diagnostics"]["decisions"][0]["reason"].stringValue())
        assertTrue(json["incumbents_coincide"].booleanValue())
    }

    @Test
    fun `candidate preparation preserves original pool refill counts and every stop reason`() {
        val source = ContractFixtures.report()
        for (reason in CandidatePreparationStopReason.entries) {
            val report = source.copy(candidates = source.candidates.copy(
                diagnostics = source.candidates.diagnostics.copy(
                    shortlistedCandidates = 16, initialShortlistedCandidates = 16,
                    reserveCandidates = 2, reserveCandidateLimit = 48, maxSizeAttempts = 32,
                    preparation = CandidatePreparationStatistics(
                        examinedCandidates = 18, sizeAttempts = 18,
                        budgetRejectedCandidates = 14, candidateErrors = 0,
                        refilledCandidates = 2, finalShortlistedCandidates = 4,
                        stopReason = reason,
                    ),
                ),
            ))
            val json = mapper.readTree(writer.render(report))
            assertEquals(3, json["schema_version"].intValue())
            val diagnostics = json["candidate_diagnostics"]
            assertEquals(16, diagnostics["shortlisted_candidates"].intValue())
            assertEquals(16, diagnostics["initial_shortlisted_candidates"].intValue())
            assertEquals(2, diagnostics["reserve_candidates"].intValue())
            assertEquals(48, diagnostics["reserve_candidate_limit"].intValue())
            assertEquals(32, diagnostics["max_size_attempts"].intValue())
            val preparation = diagnostics["preparation"]
            assertEquals(18, preparation["examined_candidates"].intValue())
            assertEquals(18, preparation["size_attempts"].intValue())
            assertEquals(14, preparation["budget_rejected_candidates"].intValue())
            assertEquals(0, preparation["candidate_errors"].intValue())
            assertEquals(2, preparation["refilled_candidates"].intValue())
            assertEquals(4, preparation["final_shortlisted_candidates"].intValue())
            assertEquals(reason.name, preparation["stop_reason"].stringValue())
            assertNull(preparation["sizeAttempts"])
        }
    }

    @Test
    fun `partial retains counts limits reasons completed phases and both plans`() {
        val report = ContractFixtures.report(status = RunStatus.PARTIAL)
        val json = mapper.readTree(writer.render(report))

        assertEquals("partial", json["status"].stringValue())
        assertEquals(5, json["diagnostics"]["statistics"]["explain_attempts"].intValue())
        assertEquals(2000, json["diagnostics"]["limits"]["max_explain_attempts"].intValue())
        assertEquals("EVALUATION_FAILED", json["diagnostics"]["stop_reasons"][0].stringValue())
        assertEquals("candidates", json["diagnostics"]["completed_phases"][3].stringValue())
        assertEquals("EXPLAIN_FAILED", json["diagnostics"]["errors"][0]["code"].stringValue())
        assertEquals(json["best_robust"], json["best_mean"])
        assertFalse(json["baseline"]["plans_by_query_id"].isNull)
    }

    @Test
    fun `failed reports preserve error and unknown baseline without a selected recommendation`() {
        val report = ContractFixtures.report(status = RunStatus.FAILED)
        val json = mapper.readTree(writer.render(report))

        assertEquals("failed", json["status"].stringValue())
        assertEquals("EXPLAIN_FAILED", json["error"]["code"].stringValue())
        assertTrue(json["baseline"].isNull)
        assertTrue(json["selected"].isNull)
        assertTrue(json["best_robust"].isNull)
        assertTrue(json["best_mean"].isNull)
        assertTrue(json["price_of_robustness"].isNull)
        assertTrue(json["incumbents_coincide"].isNull)
        assertEquals(0, json["selected_indexes"].size())
        assertTrue(json["queries"][0]["selected_cost"].isNull)
        assertTrue(json["queries"][0]["regression"].isNull)
    }

    @Test
    fun `SQL is preserved verbatim as JSON data including unicode and HTML syntax`() {
        val report = ContractFixtures.report()
        val input = requireNotNull(report.input)
        val sql = "SELECT '<script>я😀</script>' AS \"a\\b\"\nFROM public.records"
        val edited = report.copy(input = input.copy(queries = input.queries.mapIndexed { index, query ->
            if (index == 0) query.copy(sql = sql) else query
        }))
        val json = mapper.readTree(writer.render(edited))

        assertEquals(sql, json["queries"][0]["sql"].stringValue())
    }

    @Test
    fun `old report version cannot silently emit a new definition format`() {
        assertThrows(IllegalArgumentException::class.java) {
            writer.render(ContractFixtures.report().copy(schemaVersion = 2))
        }
    }

    @Test
    fun `invalid saved plan JSON prevents successful serialization`() {
        val report = ContractFixtures.report()
        val result = report.result as SearchResult.Completed
        val baseline = result.baseline.copy(plansByQueryId = result.baseline.plansByQueryId.mapValues {
            ExplainPlan("{} {}")
        })

        assertThrows(Exception::class.java) { writer.render(report.copy(result = result.copy(baseline = baseline))) }
    }
}
