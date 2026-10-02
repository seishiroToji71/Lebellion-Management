package uz.lebellion.org.web

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import uz.lebellion.auth.domain.InviteStatus
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.InviteListRow
import java.time.Instant
import java.util.UUID

/**
 * Create an invite. `role` is EMPLOYEE or BRANCH_MANAGER (never FOUNDER). For EMPLOYEE `unitId` is
 * required and branch/email/phone must be omitted; for BRANCH_MANAGER `branchId` plus at least one of
 * email/phone is required and `unitId` must be omitted. The service enforces the shape (400 on breach).
 */
data class CreateInviteRequest(
    val role: Role = Role.EMPLOYEE,
    val branchId: UUID? = null,
    val unitId: UUID? = null,
    @field:Email @field:Size(max = 320) val email: String? = null,
    @field:Size(max = 20) val phone: String? = null,
    @field:Min(1) @field:Max(43200) val expiresInMinutes: Int = 1440,
)

data class ReissueInviteRequest(
    @field:Min(1) @field:Max(43200) val expiresInMinutes: Int = 1440,
)

/** Returned only at create/reissue — carries the one-time plaintext `code`. */
data class InviteResponse(
    val id: UUID,
    val code: String,
    val role: Role,
    val branchId: UUID,
    val unitId: UUID?,
    val expiresAt: Instant,
    val createdAt: Instant,
)

/**
 * Invite metadata for list responses. NEVER carries the code (not even masked) nor email/phone —
 * the plaintext is shown once at create/reissue, and contacts are personal data. `status` is the
 * computed view (EXPIRED is derived from `expiresAt`, never stored).
 */
data class InviteSummary(
    val id: UUID,
    val role: Role,
    val branchId: UUID,
    val unitId: UUID?,
    val status: InviteStatusView,
    val createdBy: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
)

/** API-facing status: the three stored states plus the computed EXPIRED. */
enum class InviteStatusView { PENDING, USED, EXPIRED, REVOKED }

/** A stored PENDING invite whose deadline has passed reads as EXPIRED; USED/REVOKED are returned as-is. */
fun inviteStatusView(stored: InviteStatus, expiresAt: Instant, now: Instant): InviteStatusView = when (stored) {
    InviteStatus.PENDING -> if (expiresAt.isAfter(now)) InviteStatusView.PENDING else InviteStatusView.EXPIRED
    InviteStatus.USED -> InviteStatusView.USED
    InviteStatus.REVOKED -> InviteStatusView.REVOKED
}

/** Recovery invites are excluded upstream, so the keyset row always resolves a branch (`branchId!!`). */
fun InviteListRow.toSummary(now: Instant) = InviteSummary(
    id = id,
    role = role,
    branchId = branchId!!,
    unitId = unitId,
    status = inviteStatusView(status, expiresAt, now),
    createdBy = createdBy,
    createdAt = createdAt,
    expiresAt = expiresAt,
    usedAt = usedAt,
)
