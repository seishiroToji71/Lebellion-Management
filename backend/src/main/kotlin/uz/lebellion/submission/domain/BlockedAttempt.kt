package uz.lebellion.submission.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/** A recorded blocked duplicate upload — the count per (unit, item) is a signal shown to the Founder. */
@Entity
@Table(name = "blocked_attempt")
class BlockedAttempt(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "task_instance_id", nullable = false)
    var taskInstanceId: UUID,

    @Column(name = "item_id", nullable = false)
    var itemId: UUID,

    @Column(name = "unit_id", nullable = false)
    var unitId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "sha256", nullable = false, length = 64)
    var sha256: String,

    @Column(name = "dhash", nullable = false)
    var dhash: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 24)
    var reason: BlockedReason,
) : BaseEntity()
