package uz.lebellion.kpi.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.math.BigDecimal
import java.util.UUID

/**
 * The reviewer's award for one score-sheet line within an evaluation. `awardedPoints` ∈ {0, points/2,
 * points} (may be x.5). `maxPoints` is a snapshot of the line's points, taken at FINALIZE.
 */
@Entity
@Table(name = "evaluation_item")
class EvaluationItem(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "evaluation_id", nullable = false)
    var evaluationId: UUID,

    @Column(name = "score_sheet_item_id", nullable = false)
    var scoreSheetItemId: UUID,

    @Column(name = "awarded_points", nullable = false)
    var awardedPoints: BigDecimal,

    @Column(name = "max_points")
    var maxPoints: Int? = null,
) : BaseEntity()
