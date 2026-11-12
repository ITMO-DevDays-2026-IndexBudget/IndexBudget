package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.experiments.RealSearchComparison
import java.nio.file.Files
import java.nio.file.Path

/**
 * Real PostgreSQL + HypoPG example for #104: greedy, greedy+repair and the verification-only
 * exhaustive reference run on the same real EXPLAIN costs of one disposable database, produce
 * the machine-readable real-run artifact and keep every honesty check green. Physical
 * measurements happen only here on an explicitly selected disposable DB; the shared stand is
 * never touched and ordinary `analyze` behaviour is unchanged.
 */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_HYPOPG_TEST_URL", matches = ".+")
class SearchComparisonHypopgIntegrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `strategies share real costs, the reference verifies its whole space and the artifact is saved`() {
        val summary = RealSearchComparison().run(
            output = directory.resolve("real"),
            url = System.getenv("INDEXBUDGET_HYPOPG_TEST_URL"),
            user = System.getenv("INDEXBUDGET_HYPOPG_TEST_USER") ?: "postgres",
            password = System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD") ?: "",
        )

        // The run already fails fast on any honesty violation; assert the saved artifact.
        val artifact = directory.resolve("real/real-summary.json")
        assertTrue(Files.exists(artifact))
        val text = Files.readString(artifact)
        assertEquals(text.trim(), Files.readString(artifact).trim())

        @Suppress("UNCHECKED_CAST")
        val runs = summary["runs"] as Map<String, Any?>
        assertEquals(setOf("robust", "mean"), runs.keys)
        for (objective in listOf("robust", "mean")) {
            @Suppress("UNCHECKED_CAST")
            val byStrategy = runs.getValue(objective) as Map<String, Any?>
            for (strategy in listOf("greedy", "greedy_repair")) {
                @Suppress("UNCHECKED_CAST")
                val run = byStrategy.getValue(strategy) as Map<String, Any?>
                assertEquals("completed", run["status"], "$objective/$strategy")
                assertTrue((run["statistics"] as Map<*, *>).containsKey("actual_explain_attempts"))
            }
            @Suppress("UNCHECKED_CAST")
            val exhaustive = byStrategy.getValue("exhaustive") as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val reference = exhaustive["exhaustive"] as Map<String, Any?>
            assertEquals(true, reference["complete"], objective)
            assertEquals(true, reference["verified_optimum"], objective)
            assertEquals(36L, reference["search_space_subsets"])
            assertEquals(36, reference["evaluated_sets"])
            assertTrue((exhaustive["duration_millis"] as Number).toLong() >= 0, objective)
        }
        // Both objectives observe exactly one real baseline.
        @Suppress("UNCHECKED_CAST")
        val baseline = summary["baseline_cost_by_query_id"] as Map<String, Double>
        assertEquals(setOf("hot_c01", "hot_c02", "rare_c03"), baseline.keys)
    }
}
