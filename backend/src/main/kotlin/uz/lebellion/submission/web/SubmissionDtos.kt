package uz.lebellion.submission.web

import uz.lebellion.submission.domain.SubmissionStatus
import java.time.Instant
import java.util.UUID

data class PhotoRef(
    val id: UUID,
    /** Short-lived signed URL to fetch the bytes. */
    val url: String,
    val contentType: String,
    val sizeBytes: Long,
)

data class HelperRef(
    val employeeId: UUID,
    val confirmedAt: Instant?,
)

data class SubmissionResponse(
    val id: UUID,
    val taskInstanceId: UUID,
    val submittedByUserId: UUID,
    val answer: Boolean,
    val receivedAt: Instant,
    val late: Boolean,
    val status: SubmissionStatus,
    /** True when a static-scene near-duplicate was flagged for reviewer attention (not blocked). */
    val flagged: Boolean,
    val photo: PhotoRef?,
    val helpers: List<HelperRef>,
)
