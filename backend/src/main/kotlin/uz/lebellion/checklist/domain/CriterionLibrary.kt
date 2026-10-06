package uz.lebellion.checklist.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/**
 * A reusable, org-scoped master criterion. A checklist item may LINK to it instead of carrying its own
 * title/standard text, so a criterion shared across templates and positions is stored exactly once (no
 * duplicated text). The `default_*` columns seed a linked item's config, which the item may still override.
 */
@Entity
@Table(name = "criterion_library")
class CriterionLibrary(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    var type: ItemType,

    @Column(name = "title_ru", nullable = false, length = 120)
    var titleRu: String,

    @Column(name = "title_uz", nullable = false, length = 120)
    var titleUz: String,

    @Column(name = "standard_ru", length = 500)
    var standardRu: String? = null,

    @Column(name = "standard_uz", length = 500)
    var standardUz: String? = null,

    @Column(name = "original_text")
    var originalText: String? = null,

    @Column(name = "default_photo_required", nullable = false)
    var defaultPhotoRequired: Boolean = false,

    @Column(name = "default_points", nullable = false)
    var defaultPoints: Int = 0,

    @Column(name = "default_critical", nullable = false)
    var defaultCritical: Boolean = false,

    @Column(name = "default_static_scene", nullable = false)
    var defaultStaticScene: Boolean = false,

    @Column(name = "default_dhash_threshold", nullable = false)
    var defaultDhashThreshold: Int = 6,
) : BaseEntity()
