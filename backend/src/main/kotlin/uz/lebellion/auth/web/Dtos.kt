package uz.lebellion.auth.web

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uz.lebellion.auth.domain.Role
import java.util.UUID

/** Matches the `Error` schema in openapi.yaml. */
data class ApiError(val code: String, val message: String)

data class RegisterRequest(
    @field:NotBlank @field:Size(max = 255) val organizationName: String,
    @field:NotBlank @field:Size(max = 255) val fullName: String,
    @field:Email val email: String? = null,
    val phone: String? = null,
    @field:NotBlank @field:Size(min = 10, max = 128) val password: String,
)

data class LoginRequest(
    @field:NotBlank val login: String,
    @field:NotBlank val password: String,
)

data class ChangePasswordRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank @field:Size(min = 10, max = 128) val newPassword: String,
)

data class UserProfile(
    val id: UUID,
    val organizationId: UUID,
    val fullName: String,
    val role: Role,
    val active: Boolean,
    val email: String?,
    val phone: String?,
    val branchId: UUID?,
    val unitId: UUID?,
    val mustChangePassword: Boolean,
)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val user: UserProfile,
)
