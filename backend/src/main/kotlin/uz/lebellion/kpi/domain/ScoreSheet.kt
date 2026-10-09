package uz.lebellion.kpi.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/**
 * A KPI score sheet for one role. `roleKey` is the stable idempotency key (e.g. BOSH_OSHPAZ);
 * `positionSuggestion` records the intended position (wired to `position` in P3-1). Inactive sheets
 * (e.g. snabjenets/hostes, not in the position list) are kept but flagged `active=false`.
 */
@Entity
@Table(name = "score_sheet")
class ScoreSheet(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "role_key", nullable = false, length = 64)
    var roleKey: String,

    @Column(name = "name_ru", nullable = false, length = 255)
    var nameRu: String,

    @Column(name = "name_uz", nullable = false, length = 255)
    var nameUz: String,

    @Column(name = "position_suggestion", length = 128)
    var positionSuggestion: String? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,
) : BaseEntity()
