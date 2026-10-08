package uz.lebellion.review.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.review.domain.Review
import uz.lebellion.review.domain.ReviewDecision
import uz.lebellion.review.repo.ReviewRepository
import uz.lebellion.review.web.AlreadyReviewedException
import uz.lebellion.review.web.CannotReviewOwnException
import uz.lebellion.review.web.ReviewRequest
import uz.lebellion.review.web.ReviewResponse
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.repo.TaskInstanceRepository
import uz.lebellion.submission.domain.SubmissionStatus
import uz.lebellion.submission.repo.SubmissionHelperRepository
import uz.lebellion.submission.repo.SubmissionRepository
import java.util.UUID

/**
 * Review (accept/reject) of a submission. A reviewer may not review their own work; only a FOUNDER may
 * override an already-decided submission. Accept closes the zone (task DONE); reject makes the task
 * resubmittable again (SUBMITTED→PENDING, MISSED stays MISSED). Every decision is written to audit_log.
 */
@Service
class ReviewService(
    private val submissions: SubmissionRepository,
    private val helpers: SubmissionHelperRepository,
    private val taskInstances: TaskInstanceRepository,
    private val reviews: ReviewRepository,
    private val permissions: ReviewPermissions,
    private val audit: AuditLogRecorder,
) {

    @Transactional
    fun review(principal: AuthPrincipal, submissionId: UUID, req: ReviewRequest): ReviewResponse {
        val submission = submissions.findByIdAndOrganizationId(submissionId, principal.organizationId)
            ?: throw NotFoundException("submission not found")
        val task = taskInstances.findByIdAndOrganizationId(submission.taskInstanceId, principal.organizationId)
            ?: throw NotFoundException("submission not found")
        permissions.requireUnitVisible(principal, task.unitId)
        permissions.requireCanReview(principal, task.unitId)

        // a reviewer may not review their own work
        if (submission.submittedByUserId == principal.userId) throw CannotReviewOwnException()
        if (helpers.findBySubmissionIdAndEmployeeId(submissionId, principal.userId) != null) throw CannotReviewOwnException()

        // only a FOUNDER may change an already-decided submission
        val override = submission.status != SubmissionStatus.SUBMITTED
        if (override && principal.role != Role.FOUNDER) throw AlreadyReviewedException()

        reviews.save(Review(principal.organizationId, submissionId, principal.userId, req.decision, req.reason?.trim(), req.comment?.trim()))

        when (req.decision) {
            ReviewDecision.ACCEPTED -> {
                submission.status = SubmissionStatus.ACCEPTED
                task.status = TaskStatus.DONE // first accepted submission closes the zone
            }
            ReviewDecision.REJECTED -> {
                submission.status = SubmissionStatus.REJECTED
                // make the task submittable again; a late (MISSED) task stays MISSED for its late window
                if (task.status != TaskStatus.MISSED) task.status = TaskStatus.PENDING
            }
        }
        submissions.save(submission)
        taskInstances.save(task)

        audit.record(
            organizationId = principal.organizationId,
            eventType = if (req.decision == ReviewDecision.ACCEPTED) "REVIEW_ACCEPTED" else "REVIEW_REJECTED",
            actorUserId = principal.userId,
            targetType = "SUBMISSION",
            targetId = submissionId,
            metadata = mapOf("taskInstanceId" to task.id.toString(), "override" to override, "reason" to req.reason),
        )

        return ReviewResponse(
            submissionId = submissionId,
            decision = req.decision,
            reviewerUserId = principal.userId,
            submissionStatus = submission.status,
            taskInstanceId = task.id!!,
            taskStatus = task.status.name,
            override = override,
        )
    }
}
