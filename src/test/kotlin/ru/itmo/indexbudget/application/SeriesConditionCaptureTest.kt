package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*

class SeriesConditionCaptureTest {
    private val table = TableRef("public", "items")
    private val baseline = EvaluationResult(emptyList(), mapOf("q" to 10.0), mapOf("q" to ExplainPlan("{}")))
    private val versions = DatabaseVersions("16", "1.4")

    @Test
    fun `catalog ordering does not introduce drift but structural and row estimate changes do`() {
        val columns = listOf(CatalogColumn("a", "integer"), CatalogColumn("b", "integer"))
        val first = CatalogSnapshot(listOf(CatalogTable(table, columns, estimatedRows = 100.0)))
        val reordered = first.copy(tables = first.tables.map { it.copy(columns = it.columns.reversed()) })
        assertEquals(capture(first), capture(reordered))
        assertNotEquals(capture(first), capture(first.copy(tables = first.tables.map { it.copy(estimatedRows = 101.0) })))
        assertNotEquals(capture(first), capture(first.copy(tables = first.tables.map { it.copy(columns = columns.take(1)) })))
    }

    @Test
    fun `baseline cost plan and source fingerprints preserve distinct observed conditions`() {
        val catalog = CatalogSnapshot(listOf(CatalogTable(table, emptyList())))
        val first = capture(catalog)
        val newCost = SeriesConditionCapture.capture(catalog,
            baseline.copy(costByQueryId = mapOf("q" to 20.0)), versions, "source-a")
        assertNotEquals(first.baselineCosts, newCost.baselineCosts)
        assertEquals(first.catalogHash, newCost.catalogHash)
        val newPlan = SeriesConditionCapture.capture(catalog,
            baseline.copy(plansByQueryId = mapOf("q" to ExplainPlan("{\"changed\":true}"))), versions, "source-a")
        assertNotEquals(first.baselinePlansHash, newPlan.baselinePlansHash)
        assertNotEquals(first, SeriesConditionCapture.capture(catalog, baseline, versions, "source-b"))
    }

    private fun capture(catalog: CatalogSnapshot) = SeriesConditionCapture.capture(catalog, baseline, versions, "source-a")
}
