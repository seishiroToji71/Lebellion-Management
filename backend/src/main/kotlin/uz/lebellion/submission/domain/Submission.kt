package uz.lebellion.submission.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.time.Instant
import java.util.UUID

/**
 * One answer (+ optional photo) against a task_instance. `receivedAt` is the SERVER receipt instant —
 * client time is never trusted — and `late` records that it arrived after the deadline. `auto_flags`
 * (jsonb) is intentionally NOT mapped here (written via JdbcTemplate, like audit/outbox) to keep this a
 * clean JPA entity and avoid Hibernate's JSON mapper.
 */
@Entity
@Table(name = "submission")
class Submission(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "task_instance_id", nullable = false)
    var taskInstanceId: UUID,

    @Column(name = "submitted_by_user_id", nullable = false)
    var submittedByUserId: UUID,

    @Column(name = "answer", nullable = false)
    var answer: Boolean,

    @Column(name = "received_at", nullable = false)
    var receivedAt: Instant,

    @Column(name = "late", nullable = false)
    var late: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: SubmissionStatus = SubmissionStatus.SUBMITTED,
) : BaseEntity()
