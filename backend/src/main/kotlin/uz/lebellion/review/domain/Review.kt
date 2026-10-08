package uz.lebellion.review.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/**
 * A reviewer's accept/reject of a submission. Multiple rows per submission are allowed (a FOUNDER may
 * override a lead's decision); the latest row is the current verdict. Every row is also written to audit_log.
 */
@Entity
@Table(name = "review")
class Review(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "submission_id", nullable = false)
    var submissionId: UUID,

    @Column(name = "reviewer_user_id", nullable = false)
    var reviewerUserId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 8)
    var decision: ReviewDecision,

    @Column(name = "reason", length = 64)
    var reason: String? = null,

    @Column(name = "comment")
    var comment: String? = null,
) : BaseEntity()
