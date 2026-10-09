package uz.lebellion.kpi.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * Integrity сверка of the REAL client catalog (docs/seed/kpi_catalog.json), when present locally. It is
 * gitignored, so this test is SKIPPED in CI (no file) and runs on machines that have the catalog — a guard
 * against catalog regressions. Pure file parse: no Spring, no DB. Expected shape: 224 criteria, 11 sheets,
 * 410 rows, each sheet summing to 100 (incl. the Официант sheet, 25 x 4).
 */
class KpiCatalogIntegrityTest {

    private val catalog = File("../docs/seed/kpi_catalog.json")

    @Test
    fun `real catalog has 224 criteria, 11 sheets, 410 rows, each summing to 100`() {
        assumeTrue(catalog.isFile, "local client catalog not present (gitignored) — skipping")

        @Suppress("UNCHECKED_CAST")
        val doc = JsonMapper.builder().build().readValue(catalog, Map::class.java) as Map<String, Any?>
        val criteria = doc["criteria"] as List<*>
        @Suppress("UNCHECKED_CAST")
        val sheets = doc["score_sheets"] as List<Map<String, Any?>>

        assertEquals(224, criteria.size, "criteria count")
        assertEquals(11, sheets.size, "score sheet count")

        var rows = 0
        for (s in sheets) {
            @Suppress("UNCHECKED_CAST")
            val items = s["items"] as List<Map<String, Any?>>
            rows += items.size
            val sum = items.sumOf { (it["points"] as Number).toInt() }
            assertEquals(100, sum, "sheet ${s["role"]} must sum to 100")
        }
        assertEquals(410, rows, "total rows across sheets")
        assertTrue(sheets.any { it["role"] == "Официант" }, "the Официант sheet must be present")
    }
}
