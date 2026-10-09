package uz.lebellion.kpi.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/**
 * A bonus/base/penalty band of a score sheet: inclusive integer `[fromScore, toScore]`. `percent` is the
 * recommended adjustment (nullable — unknown for hall/supply). Across a sheet the bands cover 0..100 with
 * no gaps or overlaps.
 */
@Entity
@Table(name = "score_band")
class ScoreBand(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "score_sheet_id", nullable = false)
    var scoreSheetId: UUID,

    @Column(name = "from_score", nullable = false)
    var fromScore: Int,

    @Column(name = "to_score", nullable = false)
    var toScore: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "label", nullable = false, length = 16)
    var label: BandLabel,

    @Column(name = "percent")
    var percent: Int? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity() {
    /** Inclusive `[fromScore, toScore]` membership. */
    fun contains(score: Int): Boolean = score in fromScore..toScore
}
