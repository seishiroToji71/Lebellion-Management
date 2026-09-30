package uz.lebellion.org.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uz.lebellion.org.domain.Branch
import uz.lebellion.org.domain.OrgUnit
import java.time.Instant
import java.util.UUID

data class CreateBranchRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:Size(max = 1000) val address: String? = null,
)

data class BranchResponse(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val address: String?,
    val createdAt: Instant,
)

data class CreateUnitRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
)

data class UnitResponse(
    val id: UUID,
    val organizationId: UUID,
    val branchId: UUID,
    val name: String,
    val createdAt: Instant,
)

fun Branch.toResponse() = BranchResponse(id!!, organizationId, name, address, createdAt)

fun OrgUnit.toResponse() = UnitResponse(id!!, organizationId, branchId, name, createdAt)
