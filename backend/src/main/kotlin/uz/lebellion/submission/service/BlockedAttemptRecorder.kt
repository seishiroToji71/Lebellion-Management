package uz.lebellion.submission.service

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.submission.domain.BlockedAttempt
import uz.lebellion.submission.domain.BlockedReason
import uz.lebellion.submission.repo.BlockedAttemptRepository
import java.util.UUID

/**
 * Records a blocked duplicate attempt in its OWN transaction, so it survives the rollback of the
 * submission it blocks (the attempt count is a signal for the Founder — it must not be lost to the 409).
 */
@Component
class BlockedAttemptRecorder(private val attempts: BlockedAttemptRepository) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        organizationId: UUID,
        taskInstanceId: UUID,
        itemId: UUID,
        unitId: UUID,
        userId: UUID,
        sha256: String,
        dhash: Long,
        reason: BlockedReason,
    ) {
        attempts.save(
            BlockedAttempt(
                organizationId = organizationId,
                taskInstanceId = taskInstanceId,
                itemId = itemId,
                unitId = unitId,
                userId = userId,
                sha256 = sha256,
                dhash = dhash,
                reason = reason,
            ),
        )
    }
}
