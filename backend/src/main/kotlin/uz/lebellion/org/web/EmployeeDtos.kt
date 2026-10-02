package uz.lebellion.org.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import uz.lebellion.auth.domain.Role
import java.time.Instant
import java.util.UUID

data class RecoveryInviteRequest(
    @field:Min(1) @field:Max(43200) val expiresInMinutes: Int = 1440,
)

/**
 * Returned once when a recovery invite is issued. `code` re-binds the user's device at join;
 * `temporaryPassword` is set ONLY for a BRANCH_MANAGER target (a Founder-initiated password reset)
 * and is null for an EMPLOYEE, who has no password.
 */
data class RecoveryInviteResponse(
    val id: UUID,
    val code: String,
    val targetEmployeeId: UUID,
    val role: Role,
    val temporaryPassword: String?,
    val expiresAt: Instant,
    val createdAt: Instant,
)
