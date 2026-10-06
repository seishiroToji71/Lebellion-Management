package uz.lebellion.schedule.service

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import java.util.UUID

/**
 * Records a rejected submission attempt in its OWN transaction, so the record survives the rollback of
 * the submission that rejects it (preserving the attempt rather than losing it to the error).
 */
@Component
class TaskAttemptAuditor(private val audit: AuditLogRecorder) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordRejected(organizationId: UUID, actorUserId: UUID, taskInstanceId: UUID, reason: String) {
        audit.record(
            organizationId = organizationId,
            eventType = "TASK_SUBMIT_REJECTED",
            actorUserId = actorUserId,
            targetType = "TASK_INSTANCE",
            targetId = taskInstanceId,
            metadata = mapOf("reason" to reason),
        )
    }
}
