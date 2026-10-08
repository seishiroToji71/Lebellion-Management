package uz.lebellion.review.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.checklist.domain.ItemType
import uz.lebellion.checklist.repo.ChecklistItemRepository
import uz.lebellion.review.domain.ManualGrade
import uz.lebellion.review.domain.TaskScore
import uz.lebellion.review.repo.NumericBandRepository
import uz.lebellion.review.repo.TaskScoreRepository
import uz.lebellion.review.web.CannotScoreOwnException
import uz.lebellion.review.web.NotScorableException
import uz.lebellion.review.web.TaskScoreResponse
import uz.lebellion.schedule.repo.TaskInstanceRepository
import uz.lebellion.submission.repo.SubmissionHelperRepository
import uz.lebellion.submission.repo.SubmissionRepository
import java.math.BigDecimal
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Per-item scoring (recommendation input only — the system never computes pay). MANUAL uses a
 * FULL/PARTIAL/ZERO grade -> points x {1,0.5,0}; NUMERIC maps a measured value through the item's bands.
 * Requires `can_score`; a scorer may not score a task they submitted or helped on. One score per task
 * (re-scoring updates it).
 */
@Service
class ScoreService(
    private val taskInstances: TaskInstanceRepository,
    private val checklistItems: ChecklistItemRepository,
    private val taskScores: TaskScoreRepository,
    private val numericBands: NumericBandRepository,
    private val submissions: SubmissionRepository,
    private val helpers: SubmissionHelperRepository,
    private val permissions: ReviewPermissions,
    private val audit: AuditLogRecorder,
) {

    @Transactional
    fun scoreManual(principal: AuthPrincipal, taskInstanceId: UUID, grade: ManualGrade): TaskScoreResponse {
        val (task, item) = resolve(principal, taskInstanceId, ItemType.MANUAL)
        val awarded = (item.points * grade.multiplier).roundToInt()
        val score = upsert(principal, taskInstanceId, task.itemId, grade = grade, numericValue = null, awarded = awarded)
        audit.record(
            organizationId = principal.organizationId,
            eventType = "MANUAL_SCORED",
            actorUserId = principal.userId,
            targetType = "TASK_INSTANCE",
            targetId = taskInstanceId,
            metadata = mapOf("grade" to grade.name, "awardedPoints" to awarded),
        )
        return score
    }

    @Transactional
    fun scoreNumeric(principal: AuthPrincipal, taskInstanceId: UUID, value: BigDecimal): TaskScoreResponse {
        val (task, _) = resolve(principal, taskInstanceId, ItemType.NUMERIC)
        val band = numericBands
            .findByOrganizationIdAndItemIdOrderBySortOrderAscIdAsc(principal.organizationId, task.itemId)
            .firstOrNull { it.contains(value) }
            ?: throw NotScorableException("no numeric band matches the value")
        val score = upsert(principal, taskInstanceId, task.itemId, grade = null, numericValue = value, awarded = band.points)
        audit.record(
            organizationId = principal.organizationId,
            eventType = "NUMERIC_SCORED",
            actorUserId = principal.userId,
            targetType = "TASK_INSTANCE",
            targetId = taskInstanceId,
            metadata = mapOf("value" to value.toPlainString(), "awardedPoints" to band.points),
        )
        return score
    }

    private fun resolve(principal: AuthPrincipal, taskInstanceId: UUID, expected: ItemType) =
        run {
            val task = taskInstances.findByIdAndOrganizationId(taskInstanceId, principal.organizationId)
                ?: throw NotFoundException("task not found")
            permissions.requireUnitVisible(principal, task.unitId)
            permissions.requireCanScore(principal)
            val item = checklistItems.findByIdAndOrganizationId(task.itemId, principal.organizationId)
                ?: throw NotFoundException("task not found")
            if (item.type != expected) throw NotScorableException("task item is not $expected")
            requireNotSelf(principal, taskInstanceId)
            task to item
        }

    /** A scorer must not score a task they submitted or were tagged on. */
    private fun requireNotSelf(principal: AuthPrincipal, taskInstanceId: UUID) {
        for (s in submissions.findByTaskInstanceId(taskInstanceId)) {
            if (s.submittedByUserId == principal.userId) throw CannotScoreOwnException()
            if (helpers.findBySubmissionIdAndEmployeeId(s.id!!, principal.userId) != null) throw CannotScoreOwnException()
        }
    }

    private fun upsert(
        principal: AuthPrincipal,
        taskInstanceId: UUID,
        itemId: UUID,
        grade: ManualGrade?,
        numericValue: BigDecimal?,
        awarded: Int,
    ): TaskScoreResponse {
        val existing = taskScores.findByTaskInstanceId(taskInstanceId)
        val saved = if (existing != null) {
            existing.grade = grade
            existing.numericValue = numericValue
            existing.awardedPoints = awarded
            existing.reviewerUserId = principal.userId
            taskScores.save(existing)
        } else {
            taskScores.save(
                TaskScore(
                    organizationId = principal.organizationId,
                    taskInstanceId = taskInstanceId,
                    itemId = itemId,
                    reviewerUserId = principal.userId,
                    grade = grade,
                    numericValue = numericValue,
                    awardedPoints = awarded,
                ),
            )
        }
        return TaskScoreResponse(taskInstanceId, saved.itemId, saved.reviewerUserId, saved.grade, saved.numericValue, saved.awardedPoints)
    }
}
