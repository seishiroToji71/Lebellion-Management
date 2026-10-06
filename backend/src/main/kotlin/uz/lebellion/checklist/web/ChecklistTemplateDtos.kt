package uz.lebellion.checklist.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uz.lebellion.checklist.domain.ChecklistTemplate
import java.time.Instant
import java.util.UUID

data class CreateChecklistTemplateRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
)

data class ChecklistTemplateResponse(
    val id: UUID,
    val organizationId: UUID,
    val unitId: UUID,
    val positionId: UUID?,
    val name: String,
    val createdAt: Instant,
)

fun ChecklistTemplate.toResponse() = ChecklistTemplateResponse(
    id = id!!,
    organizationId = organizationId,
    unitId = unitId,
    positionId = positionId,
    name = name,
    createdAt = createdAt,
)
