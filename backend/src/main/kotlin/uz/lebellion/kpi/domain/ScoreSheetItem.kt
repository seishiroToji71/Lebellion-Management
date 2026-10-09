package uz.lebellion.kpi.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/**
 * One line of a score sheet: a KPI criterion with its per-sheet `points` (the sheet's lines sum to 100).
 * Duplicate criteria across lines are kept distinct (never merged).
 */
@Entity
@Table(name = "score_sheet_item")
class ScoreSheetItem(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "score_sheet_id", nullable = false)
    var scoreSheetId: UUID,

    @Column(name = "kpi_criterion_id", nullable = false)
    var kpiCriterionId: UUID,

    @Column(name = "points", nullable = false)
    var points: Int,

    @Column(name = "direction", length = 255)
    var direction: String? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity()
