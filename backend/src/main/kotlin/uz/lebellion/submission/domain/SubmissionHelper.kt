package uz.lebellion.submission.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.time.Instant
import java.util.UUID

/**
 * A co-worker the submitter tagged ("who did it") — stats credit everyone tagged. `confirmedAt` is set
 * only by the tagged employee themselves; a tagged employee must be a member of the same unit.
 */
@Entity
@Table(name = "submission_helper")
class SubmissionHelper(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "submission_id", nullable = false)
    var submissionId: UUID,

    @Column(name = "employee_id", nullable = false)
    var employeeId: UUID,

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null,
) : BaseEntity()
