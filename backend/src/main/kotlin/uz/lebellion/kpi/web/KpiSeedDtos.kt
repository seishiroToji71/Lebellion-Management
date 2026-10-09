package uz.lebellion.kpi.web

/** Summary of a seed run: how many criteria were upserted and how many sheets (re)built. */
data class KpiSeedResult(
    val criteria: Int,
    val sheets: Int,
)
