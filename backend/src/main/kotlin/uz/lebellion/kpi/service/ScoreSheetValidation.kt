package uz.lebellion.kpi.service

import uz.lebellion.auth.web.RequestValidationException
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure KPI scoring rules (no Spring, no DB) — easy to unit-test:
 * - a sheet's item points must sum to exactly 100;
 * - its bands must cover 0..100 with inclusive integer bounds, no gaps and no overlaps;
 * - the final score is rounded HALF-UP to an integer before the band lookup (so 90.5 -> 91).
 */
object ScoreSheetValidation {

    fun requireSumIs100(points: List<Int>) {
        val sum = points.sum()
        if (sum != 100) throw RequestValidationException("score sheet points must sum to 100, got $sum")
    }

    /** `bands` as inclusive (from, to) pairs. Must tile 0..100 exactly once. */
    fun requireBandsCover0To100(bands: List<Pair<Int, Int>>) {
        if (bands.isEmpty()) throw RequestValidationException("score sheet has no bands")
        val sorted = bands.sortedBy { it.first }
        for ((from, to) in sorted) {
            if (from > to) throw RequestValidationException("band from ($from) must be <= to ($to)")
        }
        if (sorted.first().first != 0) throw RequestValidationException("bands must start at 0")
        if (sorted.last().second != 100) throw RequestValidationException("bands must end at 100")
        for (i in 1 until sorted.size) {
            val prevTo = sorted[i - 1].second
            val curFrom = sorted[i].first
            if (curFrom == prevTo + 1) continue
            if (curFrom <= prevTo) throw RequestValidationException("bands overlap at $curFrom")
            throw RequestValidationException("gap in bands between $prevTo and $curFrom")
        }
    }

    /** Round a (possibly x.5) final score half-up to the integer used for the band lookup. */
    fun roundHalfUp(score: BigDecimal): Int = score.setScale(0, RoundingMode.HALF_UP).toInt()
}
