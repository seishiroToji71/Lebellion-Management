package uz.lebellion.kpi.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import uz.lebellion.checklist.domain.ItemType
import java.util.UUID

/**
 * A standalone KPI catalog entry (no tasks are created from it — not criterion_library, not checklist_item).
 * `title_ru/uz` are the short names from the catalog; `originalText` is the verbatim KPI line. `code`
 * (C001…) is the stable idempotency key for seeding. `checklistItemId` is reserved and unused in v1.
 */
@Entity
@Table(name = "kpi_criterion")
class KpiCriterion(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "code", nullable = false, length = 16)
    var code: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    var type: ItemType,

    @Column(name = "title_ru", nullable = false, length = 255)
    var titleRu: String,

    @Column(name = "title_uz", nullable = false, length = 255)
    var titleUz: String,

    @Column(name = "original_text")
    var originalText: String? = null,

    @Column(name = "checklist_item_id")
    var checklistItemId: UUID? = null,
) : BaseEntity()
