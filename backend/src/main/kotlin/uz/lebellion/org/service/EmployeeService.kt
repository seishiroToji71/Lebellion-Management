package uz.lebellion.org.service

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.auth.config.AuthProperties
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.domain.Invite
import uz.lebellion.auth.domain.RevocationReason
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.ratelimit.RateLimiter
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.repo.InviteRepository
import uz.lebellion.auth.repo.RefreshTokenRepository
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.service.AuthResponseFactory
import uz.lebellion.auth.token.HmacCodec
import uz.lebellion.auth.token.SecureCodeGenerator
import uz.lebellion.auth.web.ConflictException
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.auth.web.RateLimitedException
import uz.lebellion.auth.web.UserProfile
import uz.lebellion.common.web.Page
import uz.lebellion.common.web.pageOf
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.web.RecoveryInviteRequest
import uz.lebellion.org.web.RecoveryInviteResponse
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Throwaway value for a disabled filter/scope dimension (the matching flag is false). */
private val UNUSED_UUID: UUID = UUID(0L, 0L)

/**
 * Employee management: list (scoped + filtered), deactivate (immediate sign-out), and recovery invites.
 * A recovery invite re-binds a user to a new device; for a BRANCH_MANAGER target it doubles as a
 * Founder-initiated password reset (temp password + immediate lockout). Scoping mirrors the rest of
 * org management: FOUNDER across the org, BRANCH_MANAGER confined to EMPLOYEEs of their own branch.
 */
@Service
class EmployeeService(
    private val users: AppUserRepository,
    private val units: UnitRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val invites: InviteRepository,
    private val codeGen: SecureCodeGenerator,
    @Qualifier("inviteHmacCodec") private val inviteHmac: HmacCodec,
    private val encoder: PasswordEncoder,
    private val profiles: AuthResponseFactory,
    private val auditLog: AuditLogRecorder,
    private val rateLimiter: RateLimiter,
    private val props: AuthProperties,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(
        principal: AuthPrincipal,
        limit: Int,
        cursor: String?,
        branchId: UUID?,
        unitId: UUID?,
        active: Boolean?,
    ): Page<UserProfile> {
        requireFounderOrManager(principal)
        val scope = branchScopeOf(principal)
        val (afterCreatedAt, afterId) = cursorFloor(cursor)
        val rows = users.pageEmployees(
            orgId = principal.organizationId,
            scoped = scope != null,
            scopeBranch = scope ?: UNUSED_UUID,
            filterByBranch = branchId != null,
            branchFilter = branchId ?: UNUSED_UUID,
            filterByUnit = unitId != null,
            unitFilter = unitId ?: UNUSED_UUID,
            filterByActive = active != null,
            activeValue = active ?: false,
            afterCreatedAt = afterCreatedAt,
            afterId = afterId,
            pageable = PageRequest.ofSize(limit + 1),
        )
        return pageOf(rows, limit, { it.createdAt }, { it.id!! }) { profiles.profile(it) }
    }

    @Transactional
    fun deactivate(principal: AuthPrincipal, employeeId: UUID): UserProfile {
        requireFounderOrManager(principal)
        val now = clock.instant()
        val target = users.findByIdAndOrganizationIdForUpdate(employeeId, principal.organizationId)
            ?: throw NotFoundException("employee not found")
        requireInScope(principal, target)
        if (target.id == principal.userId) throw ConflictException("you cannot deactivate yourself")
        if (target.role == Role.FOUNDER) throw ConflictException("a founder cannot be deactivated")

        target.isActive = false
        target.tokenVersion += 1 // outstanding ACCESS tokens die on the next request, not ~15 min later
        users.save(target)
        refreshTokens.revokeAllActiveForUser(target.id!!, now, RevocationReason.DEACTIVATED)
        audit("EMPLOYEE_DEACTIVATED", principal, target.id!!, mapOf("role" to target.role.name))
        return profiles.profile(target)
    }

    @Transactional
    fun issueRecoveryInvite(principal: AuthPrincipal, employeeId: UUID, req: RecoveryInviteRequest): RecoveryInviteResponse {
        requireFounderOrManager(principal)
        rateLimit("recovery-invite", principal.userId)
        val now = clock.instant()
        val target = users.findByIdAndOrganizationIdForUpdate(employeeId, principal.organizationId)
            ?: throw NotFoundException("employee not found")
        requireInScope(principal, target)
        if (target.role == Role.FOUNDER) throw ConflictException("a founder cannot be recovered via this endpoint")
        if (!target.isActive) throw ConflictException("an inactive user cannot be recovered")

        val plaintext = codeGen.inviteCode()
        val invite = Invite(
            organizationId = principal.organizationId,
            codeHmac = inviteHmac.hmacHex(plaintext),
            role = target.role,
            expiresAt = now.plus(Duration.ofMinutes(req.expiresInMinutes.toLong())),
            createdBy = principal.userId,
            targetEmployeeId = target.id,
        )

        // MANAGER target = Founder-initiated password reset for a (likely) compromised account:
        // set a temp password + mustChangePassword and kill every live session RIGHT NOW (don't wait
        // for join). EMPLOYEE target = device change: no password, sessions revoked later at join.
        val temporaryPassword: String? = if (target.role == Role.BRANCH_MANAGER) {
            val temp = codeGen.inviteCode(12)
            target.passwordHash = encoder.encode(temp)
            target.mustChangePassword = true
            target.tokenVersion += 1
            users.save(target)
            refreshTokens.revokeAllActiveForUser(target.id!!, now, RevocationReason.PASSWORD_RESET)
            temp
        } else {
            null
        }

        val saved = invites.save(invite)
        val eventType = if (target.role == Role.BRANCH_MANAGER) "PASSWORD_RESET_BY_FOUNDER" else "RECOVERY_INVITE_CREATED"
        audit(eventType, principal, target.id!!, mapOf("role" to target.role.name, "inviteId" to saved.id.toString()))
        return RecoveryInviteResponse(
            id = saved.id!!,
            code = plaintext,
            targetEmployeeId = target.id!!,
            role = target.role,
            temporaryPassword = temporaryPassword,
            expiresAt = saved.expiresAt,
            createdAt = saved.createdAt,
        )
    }

    /**
     * A FOUNDER may act on any user in the org; a BRANCH_MANAGER only on an EMPLOYEE of their own branch.
     * Anything outside that is reported as 404 (never revealing it exists), so a manager cannot probe for
     * other managers, the founder, or other branches.
     */
    private fun requireInScope(principal: AuthPrincipal, target: AppUser) {
        val scope = branchScopeOf(principal) ?: return // FOUNDER: whole org
        if (target.role != Role.EMPLOYEE || branchOf(target) != scope) throw NotFoundException("employee not found")
    }

    private fun branchOf(user: AppUser): UUID? =
        user.unitId?.let { units.findByIdAndOrganizationId(it, user.organizationId)?.branchId } ?: user.branchId

    private fun rateLimit(action: String, userId: UUID) {
        val rule = props.rule(action)
        if (!rateLimiter.tryAcquire("$action:$userId", RateLimiter.Rule(rule.limit, rule.window))) {
            throw RateLimitedException(60)
        }
    }

    private fun audit(eventType: String, principal: AuthPrincipal, targetId: UUID, metadata: Map<String, Any?>) {
        auditLog.record(
            organizationId = principal.organizationId,
            eventType = eventType,
            actorUserId = principal.userId,
            targetType = "USER",
            targetId = targetId,
            metadata = metadata,
        )
    }
}
