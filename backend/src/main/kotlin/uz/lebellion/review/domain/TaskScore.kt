package uz.lebellion.review.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.math.BigDecimal
import java.util.UUID

/**
 * A per-task score (one per task_instance; re-scoring updates it). MANUAL carries a [grade]; NUMERIC
 * carries the measured [numericValue]. `awardedPoints` is what the system computed (recommendation input
 * only — the system never computes pay).
 */
@Entity
@Table(name = "task_score")
class TaskScore(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "task_instance_id", nullable = false)
    var taskInstanceId: UUID,

    @Column(name = "item_id", nullable = false)
    var itemId: UUID,

    @Column(name = "reviewer_user_id", nullable = false)
    var reviewerUserId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", length = 8)
    var grade: ManualGrade? = null,

    @Column(name = "numeric_value")
    var numericValue: BigDecimal? = null,

    @Column(name = "awarded_points", nullable = false)
    var awardedPoints: Int,
) : BaseEntity()
