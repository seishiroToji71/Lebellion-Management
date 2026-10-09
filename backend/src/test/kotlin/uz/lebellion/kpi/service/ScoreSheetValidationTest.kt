package uz.lebellion.kpi.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import uz.lebellion.auth.web.RequestValidationException
import java.math.BigDecimal

class ScoreSheetValidationTest {

    @Test
    fun `sum must be exactly 100`() {
        ScoreSheetValidation.requireSumIs100(listOf(50, 30, 20)) // ok
        assertThrows(RequestValidationException::class.java) { ScoreSheetValidation.requireSumIs100(listOf(50, 30, 19)) }
        assertThrows(RequestValidationException::class.java) { ScoreSheetValidation.requireSumIs100(listOf(50, 30, 21)) }
    }

    @Test
    fun `bands must tile 0 to 100 with no gaps or overlaps`() {
        // chef bands, inclusive, contiguous
        ScoreSheetValidation.requireBandsCover0To100(listOf(0 to 85, 86 to 90, 91 to 95, 96 to 100))
        // gap between 90 and 92
        assertThrows(RequestValidationException::class.java) {
            ScoreSheetValidation.requireBandsCover0To100(listOf(0 to 85, 86 to 90, 92 to 95, 96 to 100))
        }
        // overlap 90..86
        assertThrows(RequestValidationException::class.java) {
            ScoreSheetValidation.requireBandsCover0To100(listOf(0 to 86, 86 to 90, 91 to 95, 96 to 100))
        }
        // does not start at 0
        assertThrows(RequestValidationException::class.java) {
            ScoreSheetValidation.requireBandsCover0To100(listOf(1 to 85, 86 to 100))
        }
        // does not end at 100
        assertThrows(RequestValidationException::class.java) {
            ScoreSheetValidation.requireBandsCover0To100(listOf(0 to 85, 86 to 99))
        }
    }

    @Test
    fun `final score rounds half-up before the band lookup`() {
        assertEquals(91, ScoreSheetValidation.roundHalfUp(BigDecimal("90.5")))
        assertEquals(90, ScoreSheetValidation.roundHalfUp(BigDecimal("90.4")))
        assertEquals(86, ScoreSheetValidation.roundHalfUp(BigDecimal("85.5")))
        assertEquals(100, ScoreSheetValidation.roundHalfUp(BigDecimal("100.0")))
        assertEquals(0, ScoreSheetValidation.roundHalfUp(BigDecimal("0.0")))
    }
}
