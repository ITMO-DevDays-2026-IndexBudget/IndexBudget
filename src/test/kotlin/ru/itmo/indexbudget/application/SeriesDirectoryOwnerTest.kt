package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SeriesDirectoryOwnerTest {
    @TempDir lateinit var directory: Path
    private val request = BudgetSeriesRequest(SeriesBudget.STORAGE, listOf("0", "1", "2").map(::BigDecimal))

    @Test
    fun `a series published after another initial validation cannot be overwritten`() {
        val output = directory.resolve("series")
        OutputDirectoryValidator().validate(output) // Paused run A has already validated this path.
        val winnerContext = RunContext(runId = "series-b")
        val owner = SeriesDirectoryOwner.claim(output, winnerContext.runId)
        val writer = BudgetSeriesWriter(owner, ContractFixtures.workload(), request, winnerContext)
        val result = BudgetSeriesResult(SeriesStatus.FAILED, emptyList())
        writer.publish(result, null, null, finished = true)
        val original = Files.readAllBytes(output.resolve("series.json"))
        val artifacts = Files.createDirectory(output.resolve("point-01"))
        Files.writeString(artifacts.resolve("indexes.sql"), "published by B")

        val rejected = assertThrows(AnalysisException::class.java) { SeriesDirectoryOwner.claim(output, "series-a") }

        assertEquals(ErrorCode.EXPORT_FAILED, rejected.error.code)
        assertArrayEquals(original, Files.readAllBytes(output.resolve("series.json")))
        assertEquals("published by B", Files.readString(artifacts.resolve("indexes.sql")))
        assertEquals("series-b", Files.readString(output.resolve(SeriesDirectoryOwner.MARKER)))
    }

    @Test
    fun `two already validated concurrent claimants elect exactly one summary owner`() {
        val output = Files.createDirectory(directory.resolve("series"))
        val validated = CountDownLatch(2)
        Executors.newFixedThreadPool(2).use { executor ->
            val tasks = listOf("series-a", "series-b").map { seriesId -> executor.submit(Callable {
                OutputDirectoryValidator().validate(output)
                validated.countDown()
                assertTrue(validated.await(5, TimeUnit.SECONDS))
                try {
                    val owner = SeriesDirectoryOwner.claim(output, seriesId)
                    val writer = BudgetSeriesWriter(owner, ContractFixtures.workload(), request, RunContext(runId = seriesId))
                    writer.publish(BudgetSeriesResult(SeriesStatus.FAILED, emptyList()), null, null, finished = true)
                    seriesId
                } catch (failure: AnalysisException) {
                    assertEquals(ErrorCode.EXPORT_FAILED, failure.error.code)
                    null
                }
            }) }
            val winners = tasks.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, winners.size)
            val summary = tools.jackson.databind.json.JsonMapper().readTree(Files.readString(output.resolve("series.json")))
            assertEquals(winners.single(), summary["series_id"].stringValue())
            assertEquals(winners.single(), Files.readString(output.resolve(SeriesDirectoryOwner.MARKER)))
        }
    }

    @Test
    fun `an owner cannot update a summary after its ownership marker changes`() {
        val output = directory.resolve("series")
        val context = RunContext(runId = "series-a")
        val writer = BudgetSeriesWriter(SeriesDirectoryOwner.claim(output, context.runId), ContractFixtures.workload(), request, context)
        writer.publish(BudgetSeriesResult(SeriesStatus.FAILED, emptyList()), null, null, finished = false)
        val original = Files.readAllBytes(output.resolve("series.json"))
        Files.writeString(output.resolve(SeriesDirectoryOwner.MARKER), "series-b")
        assertEquals(ErrorCode.EXPORT_FAILED, assertThrows(AnalysisException::class.java) {
            writer.publish(BudgetSeriesResult(SeriesStatus.FAILED, emptyList()), null, null, finished = true)
        }.error.code)
        assertArrayEquals(original, Files.readAllBytes(output.resolve("series.json")))
    }
}
