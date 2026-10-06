package uz.lebellion.checklist.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uz.lebellion.checklist.domain.CriterionLibrary
import uz.lebellion.checklist.domain.ItemType
import java.time.Instant
import java.util.UUID

data class CreateCriterionRequest(
    val type: ItemType,
    @field:NotBlank @field:Size(max = 120) val titleRu: String,
    @field:NotBlank @field:Size(max = 120) val titleUz: String,
    @field:Size(max = 500) val standardRu: String? = null,
    @field:Size(max = 500) val standardUz: String? = null,
    @field:Size(max = 4000) val originalText: String? = null,
    val photoRequired: Boolean = false,
    @field:Min(0) val points: Int = 0,
    val critical: Boolean = false,
    val staticScene: Boolean = false,
    @field:Min(0) @field:Max(64) val dhashThreshold: Int = 6,
)

data class CriterionResponse(
    val id: UUID,
    val organizationId: UUID,
    val type: ItemType,
    val titleRu: String,
    val titleUz: String,
    val standardRu: String?,
    val standardUz: String?,
    val originalText: String?,
    val photoRequired: Boolean,
    val points: Int,
    val critical: Boolean,
    val staticScene: Boolean,
    val dhashThreshold: Int,
    val createdAt: Instant,
)

fun CriterionLibrary.toResponse() = CriterionResponse(
    id = id!!,
    organizationId = organizationId,
    type = type,
    titleRu = titleRu,
    titleUz = titleUz,
    standardRu = standardRu,
    standardUz = standardUz,
    originalText = originalText,
    photoRequired = defaultPhotoRequired,
    points = defaultPoints,
    critical = defaultCritical,
    staticScene = defaultStaticScene,
    dhashThreshold = defaultDhashThreshold,
    createdAt = createdAt,
)
