package uz.lebellion.checklist.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import uz.lebellion.checklist.domain.ChecklistItem
import uz.lebellion.checklist.domain.CriterionLibrary
import uz.lebellion.checklist.domain.ItemType
import java.time.Instant
import java.util.UUID

/**
 * Create a checklist item. Provide `criterionId` to LINK a library criterion (its `type` and text are
 * inherited; the scalar config fields, when present, override the criterion's defaults). Omit it for an
 * AD-HOC item, in which case `type`, `titleRu` and `titleUz` are required. The scalar fields are nullable
 * so "not provided" (inherit / default) is distinct from an explicit value.
 */
data class CreateChecklistItemRequest(
    val criterionId: UUID? = null,
    val type: ItemType? = null,
    @field:Size(max = 120) val titleRu: String? = null,
    @field:Size(max = 120) val titleUz: String? = null,
    @field:Size(max = 500) val standardRu: String? = null,
    @field:Size(max = 500) val standardUz: String? = null,
    @field:Size(max = 4000) val originalText: String? = null,
    val photoRequired: Boolean? = null,
    @field:Min(0) val points: Int? = null,
    val critical: Boolean? = null,
    val staticScene: Boolean? = null,
    @field:Min(0) @field:Max(64) val dhashThreshold: Int? = null,
    val sortOrder: Int = 0,
)

data class ChecklistItemResponse(
    val id: UUID,
    val organizationId: UUID,
    val templateId: UUID,
    val criterionId: UUID?,
    val type: ItemType,
    val titleRu: String?,
    val titleUz: String?,
    val standardRu: String?,
    val standardUz: String?,
    val originalText: String?,
    val photoRequired: Boolean,
    val points: Int,
    val critical: Boolean,
    val staticScene: Boolean,
    val dhashThreshold: Int,
    val sortOrder: Int,
    val createdAt: Instant,
)

/**
 * Builds the response, resolving the title/standard text from the linked [criterion] (the item itself
 * stores no copy). An ad-hoc item (criterionId == null) returns its own inline text.
 */
fun ChecklistItem.toResponse(criterion: CriterionLibrary?): ChecklistItemResponse {
    val linked = criterionId != null
    return ChecklistItemResponse(
        id = id!!,
        organizationId = organizationId,
        templateId = templateId,
        criterionId = criterionId,
        type = type,
        titleRu = if (linked) criterion?.titleRu else titleRu,
        titleUz = if (linked) criterion?.titleUz else titleUz,
        standardRu = if (linked) criterion?.standardRu else standardRu,
        standardUz = if (linked) criterion?.standardUz else standardUz,
        originalText = if (linked) criterion?.originalText else originalText,
        photoRequired = photoRequired,
        points = points,
        critical = critical,
        staticScene = staticScene,
        dhashThreshold = dhashThreshold,
        sortOrder = sortOrder,
        createdAt = createdAt,
    )
}
