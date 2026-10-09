package uz.lebellion.kpi.web

import uz.lebellion.kpi.domain.BandLabel
import uz.lebellion.kpi.domain.EvaluationStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CreateEvaluationRequest(
    val employeeId: UUID,
    val scoreSheetId: UUID,
    /** Any day of the target month; normalised to the 1st (Asia/Tashkent is implied by the calendar month). */
    val periodMonth: LocalDate,
)

data class SetAwardRequest(
    val scoreSheetItemId: UUID,
    val awardedPoints: BigDecimal,
)

data class EvaluationItemView(
    val scoreSheetItemId: UUID,
    val criterionCode: String,
    val titleRu: String,
    /** Max for this line — live sheet points while DRAFT, snapshot while FINALIZED. */
    val points: Int,
    /** Reviewer's award, or null if not scored yet. */
    val awardedPoints: BigDecimal?,
)

data class RecommendationView(
    val label: BandLabel,
    val percent: Int?,
)

data class EvaluationResponse(
    val id: UUID,
    val employeeId: UUID,
    val scoreSheetId: UUID,
    val periodMonth: LocalDate,
    val status: EvaluationStatus,
    val reviewerUserId: UUID,
    /** Live sum while DRAFT; frozen snapshot while FINALIZED (may be x.5). */
    val finalScore: BigDecimal,
    val recommendation: RecommendationView?,
    val items: List<EvaluationItemView>,
)
