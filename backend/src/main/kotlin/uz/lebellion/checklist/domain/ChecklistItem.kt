package uz.lebellion.checklist.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/**
 * One line of a checklist template. Either LINKS to a library criterion (`criterionId` set, the inline
 * title/standard columns stay null and the text is read from the library — no duplication) or is AD-HOC
 * (`criterionId` null, the inline columns carry the text). Scalar config is materialised here so it can be
 * overridden per item; a linked item seeds it from the criterion's defaults at creation time.
 *
 * numeric_scale (for NUMERIC items) is intentionally absent — it arrives with scoring in P2-6.
 */
@Entity
@Table(name = "checklist_item")
class ChecklistItem(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "template_id", nullable = false)
    var templateId: UUID,

    @Column(name = "criterion_id")
    var criterionId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    var type: ItemType,

    @Column(name = "title_ru", length = 120)
    var titleRu: String? = null,

    @Column(name = "title_uz", length = 120)
    var titleUz: String? = null,

    @Column(name = "standard_ru", length = 500)
    var standardRu: String? = null,

    @Column(name = "standard_uz", length = 500)
    var standardUz: String? = null,

    @Column(name = "original_text")
    var originalText: String? = null,

    @Column(name = "photo_required", nullable = false)
    var photoRequired: Boolean = false,

    @Column(name = "points", nullable = false)
    var points: Int = 0,

    @Column(name = "critical", nullable = false)
    var critical: Boolean = false,

    @Column(name = "static_scene", nullable = false)
    var staticScene: Boolean = false,

    @Column(name = "dhash_threshold", nullable = false)
    var dhashThreshold: Int = 6,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity()
