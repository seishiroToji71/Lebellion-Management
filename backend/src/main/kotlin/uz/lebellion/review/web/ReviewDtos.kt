package uz.lebellion.review.web

import jakarta.validation.constraints.Size
import uz.lebellion.review.domain.ManualGrade
import uz.lebellion.review.domain.ReviewDecision
import uz.lebellion.submission.domain.SubmissionStatus
import java.math.BigDecimal
import java.util.UUID

// --- review ----------------------------------------------------------------

data class ReviewRequest(
    val decision: ReviewDecision,
    @field:Size(max = 64) val reason: String? = null,
    @field:Size(max = 4000) val comment: String? = null,
)

data class ReviewResponse(
    val submissionId: UUID,
    val decision: ReviewDecision,
    val reviewerUserId: UUID,
    val submissionStatus: SubmissionStatus,
    val taskInstanceId: UUID,
    val taskStatus: String,
    /** True when this decision overrode a previous one (a FOUNDER reversing a lead). */
    val override: Boolean,
)

// --- scoring ---------------------------------------------------------------

data class ManualScoreRequest(val grade: ManualGrade)

data class NumericScoreRequest(val value: BigDecimal)

data class TaskScoreResponse(
    val taskInstanceId: UUID,
    val itemId: UUID,
    val reviewerUserId: UUID,
    val grade: ManualGrade?,
    val numericValue: BigDecimal?,
    val awardedPoints: Int,
)

// --- numeric bands ---------------------------------------------------------

data class CreateNumericBandRequest(
    val lowerBound: BigDecimal? = null,
    val upperBound: BigDecimal? = null,
    val points: Int,
    @field:Size(max = 64) val label: String? = null,
    val sortOrder: Int = 0,
)

data class NumericBandResponse(
    val id: UUID,
    val itemId: UUID,
    val lowerBound: BigDecimal?,
    val upperBound: BigDecimal?,
    val points: Int,
    val label: String?,
    val sortOrder: Int,
)

// --- zone progress ---------------------------------------------------------

data class ZoneProgressResponse(
    val scheduleId: UUID,
    val periodKey: String,
    val total: Long,
    val done: Long,
)
