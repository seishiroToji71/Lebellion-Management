package uz.lebellion.kpi.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * A monthly evaluation of one employee against one score sheet (subject = employee × sheet × month). The
 * reviewer sets per-item awards (no v1 auto-hints). FINALIZE computes `finalScore` (Σ awarded, may be
 * x.5), resolves the band (half-up), snapshots `bandLabel`/`bandPercent`, and freezes the row.
 */
@Entity
@Table(name = "evaluation")
class Evaluation(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "employee_id", nullable = false)
    var employeeId: UUID,

    @Column(name = "score_sheet_id", nullable = false)
    var scoreSheetId: UUID,

    @Column(name = "period_month", nullable = false)
    var periodMonth: LocalDate,

    @Column(name = "reviewer_user_id", nullable = false)
    var reviewerUserId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: EvaluationStatus = EvaluationStatus.DRAFT,

    @Column(name = "final_score")
    var finalScore: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "band_label", length = 16)
    var bandLabel: BandLabel? = null,

    @Column(name = "band_percent")
    var bandPercent: Int? = null,
) : BaseEntity()
