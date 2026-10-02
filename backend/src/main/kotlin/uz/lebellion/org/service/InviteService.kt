package uz.lebellion.org.service

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.auth.config.AuthProperties
import uz.lebellion.auth.domain.Invite
import uz.lebellion.auth.domain.InviteStatus
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.ratelimit.RateLimiter
import uz.lebellion.auth.repo.InviteRepository
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.token.HmacCodec
import uz.lebellion.auth.token.SecureCodeGenerator
import uz.lebellion.auth.web.ConflictException
import uz.lebellion.auth.web.ForbiddenException
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.auth.web.RateLimitedException
import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.common.web.Page
import uz.lebellion.common.web.pageOf
import uz.lebellion.org.repo.BranchRepository
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.web.CreateInviteRequest
import uz.lebellion.org.web.InviteResponse
import uz.lebellion.org.web.InviteStatusView
import uz.lebellion.org.web.InviteSummary
import uz.lebellion.org.web.ReissueInviteRequest
import uz.lebellion.org.web.toSummary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Far-future upper bound for the always-applied `expiresAt` window (well within timestamptz range). */
private val FAR_FUTURE: Instant = Instant.parse("9999-12-31T23:59:59Z")

/**
 * Invite management (create / list / reissue / revoke). Minting and reissuing a code are rate limited
 * per caller — a compromised FOUNDER/MANAGER account must not be able to issue secrets without bound.
 * FOUNDER may invite anywhere in the org; a BRANCH_MANAGER may only invite EMPLOYEEs into their own
 * branch and only manage EMPLOYEE invites there (an out-of-scope invite is reported as 404, no leak).
 */
@Service
class InviteService(
    private val invites: InviteRepository,
    private val units: UnitRepository,
    private val branches: BranchRepository,
    private val codeGen: SecureCodeGenerator,
    @Qualifier("inviteHmacCodec") private val inviteHmac: HmacCodec,
    private val auditLog: AuditLogRecorder,
    private val rateLimiter: RateLimiter,
    private val props: AuthProperties,
    private val clock: Clock,
) {

    @Transactional
    fun create(principal: AuthPrincipal, req: CreateInviteRequest): InviteResponse {
        requireFounderOrManager(principal)
        rateLimit("invite-create", principal.userId)
        val now = clock.instant()
        return when (req.role) {
            Role.EMPLOYEE -> createEmployee(principal, req, now)
            Role.BRANCH_MANAGER -> createManager(principal, req, now)
            else -> throw RequestValidationException("role must be EMPLOYEE or BRANCH_MANAGER")
        }
    }

    private fun createEmployee(principal: AuthPrincipal, req: CreateInviteRequest, now: Instant): InviteResponse {
        if (req.branchId != null || req.email != null || req.phone != null) {
            throw RequestValidationException("branchId/email/phone are not allowed for an employee invite")
        }
        val unitId = req.unitId ?: throw RequestValidationException("unitId is required for an employee invite")
        val scope = branchScopeOf(principal)
        val unit = units.findByIdAndOrganizationId(unitId, principal.organizationId)
            ?: throw NotFoundException("unit not found")
        if (scope != null && unit.branchId != scope) throw NotFoundException("unit not found")

        val plaintext = codeGen.inviteCode()
        val invite = invites.save(
            Invite(
                organizationId = principal.organizationId,
                codeHmac = inviteHmac.hmacHex(plaintext),
                role = Role.EMPLOYEE,
                expiresAt = expiryFrom(now, req.expiresInMinutes),
                createdBy = principal.userId,
                unitId = unit.id!!,
            ),
        )
        audit(principal, "INVITE_CREATED", invite, mapOf("role" to Role.EMPLOYEE.name, "unitId" to unit.id.toString()))
        return invite.toResponse(plaintext, unit.branchId)
    }

    private fun createManager(principal: AuthPrincipal, req: CreateInviteRequest, now: Instant): InviteResponse {
        if (principal.role != Role.FOUNDER) throw ForbiddenException("only a founder may invite a branch manager")
        if (req.unitId != null) throw RequestValidationException("unitId is not allowed for a branch-manager invite")
        val branchId = req.branchId ?: throw RequestValidationException("branchId is required for a branch-manager invite")
        val email = req.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val phone = req.phone?.trim()?.takeIf { it.isNotEmpty() }
        if (email == null && phone == null) {
            throw RequestValidationException("a branch-manager invite requires an email or phone")
        }
        val branch = branches.findByIdAndOrganizationId(branchId, principal.organizationId)
            ?: throw NotFoundException("branch not found")

        val plaintext = codeGen.inviteCode()
        val invite = invites.save(
            Invite(
                organizationId = principal.organizationId,
                codeHmac = inviteHmac.hmacHex(plaintext),
                role = Role.BRANCH_MANAGER,
                expiresAt = expiryFrom(now, req.expiresInMinutes),
                createdBy = principal.userId,
                branchId = branch.id!!,
                email = email,
                phone = phone,
            ),
        )
        audit(principal, "INVITE_CREATED", invite, mapOf("role" to Role.BRANCH_MANAGER.name, "branchId" to branch.id.toString()))
        return invite.toResponse(plaintext, branch.id!!)
    }

    @Transactional(readOnly = true)
    fun list(principal: AuthPrincipal, limit: Int, cursor: String?, statusFilter: InviteStatusView?): Page<InviteSummary> {
        requireFounderOrManager(principal)
        val scope = branchScopeOf(principal)
        val now = clock.instant()
        val (status, expiresAfter, expiresBefore) = statusWindow(statusFilter, now)
        val (afterCreatedAt, afterId) = cursorFloor(cursor)
        val pageable = PageRequest.ofSize(limit + 1)
        val org = principal.organizationId
        val rows = when {
            scope != null && status != null ->
                invites.pageListInBranchByStatus(org, scope, status, expiresAfter, expiresBefore, afterCreatedAt, afterId, pageable)
            scope != null ->
                invites.pageListInBranch(org, scope, expiresAfter, expiresBefore, afterCreatedAt, afterId, pageable)
            status != null ->
                invites.pageListByStatus(org, status, expiresAfter, expiresBefore, afterCreatedAt, afterId, pageable)
            else ->
                invites.pageList(org, expiresAfter, expiresBefore, afterCreatedAt, afterId, pageable)
        }
        return pageOf(rows, limit, { it.createdAt }, { it.id }) { it.toSummary(now) }
    }

    @Transactional
    fun reissue(principal: AuthPrincipal, inviteId: UUID, req: ReissueInviteRequest): InviteResponse {
        requireFounderOrManager(principal)
        rateLimit("invite-reissue", principal.userId)
        val now = clock.instant()
        val invite = invites.findByIdAndOrganizationIdForUpdate(inviteId, principal.organizationId)
            ?: throw NotFoundException("invite not found")
        val branchId = resolveScopedBranch(principal, invite)
        // EXPIRED is stored PENDING, so a lapsed code is reissuable; USED/REVOKED are terminal.
        if (invite.status != InviteStatus.PENDING) throw ConflictException("invite is not in a reissuable state")

        val plaintext = codeGen.inviteCode()
        invite.codeHmac = inviteHmac.hmacHex(plaintext) // overwriting the hash kills the old code and installs the new one
        invite.expiresAt = expiryFrom(now, req.expiresInMinutes)
        invites.save(invite)
        audit(principal, "INVITE_REISSUED", invite, mapOf("role" to invite.role.name))
        return invite.toResponse(plaintext, branchId)
    }

    @Transactional
    fun revoke(principal: AuthPrincipal, inviteId: UUID) {
        requireFounderOrManager(principal)
        val invite = invites.findByIdAndOrganizationIdForUpdate(inviteId, principal.organizationId)
            ?: throw NotFoundException("invite not found")
        resolveScopedBranch(principal, invite)
        when (invite.status) {
            InviteStatus.REVOKED -> return // idempotent: already revoked
            InviteStatus.USED -> throw ConflictException("a used invite cannot be revoked")
            InviteStatus.PENDING -> {
                invite.status = InviteStatus.REVOKED
                invites.save(invite)
                audit(principal, "INVITE_REVOKED", invite, mapOf("role" to invite.role.name))
            }
        }
    }

    /**
     * Resolves the invite's branch while enforcing the caller's scope. A FOUNDER may touch any invite
     * in the org; a BRANCH_MANAGER only an EMPLOYEE invite whose unit is in their branch (anything else
     * is reported as 404, never revealing it exists). Recovery invites have no branch → 404 here.
     */
    private fun resolveScopedBranch(principal: AuthPrincipal, invite: Invite): UUID {
        val scope = branchScopeOf(principal)
        if (scope == null) {
            val viaUnit = invite.unitId?.let { units.findByIdAndOrganizationId(it, principal.organizationId)?.branchId }
            return viaUnit ?: invite.branchId ?: throw NotFoundException("invite not found")
        }
        val unitId = invite.unitId ?: throw NotFoundException("invite not found")
        val unit = units.findByIdAndOrganizationId(unitId, principal.organizationId)
            ?: throw NotFoundException("invite not found")
        if (unit.branchId != scope) throw NotFoundException("invite not found")
        return unit.branchId
    }

    /** Maps a requested status view to (stored status, expiresAt window); see InviteRepository. */
    private fun statusWindow(filter: InviteStatusView?, now: Instant): Triple<InviteStatus?, Instant, Instant> = when (filter) {
        null -> Triple(null, Instant.EPOCH, FAR_FUTURE)
        InviteStatusView.PENDING -> Triple(InviteStatus.PENDING, now, FAR_FUTURE)
        InviteStatusView.EXPIRED -> Triple(InviteStatus.PENDING, Instant.EPOCH, now)
        InviteStatusView.USED -> Triple(InviteStatus.USED, Instant.EPOCH, FAR_FUTURE)
        InviteStatusView.REVOKED -> Triple(InviteStatus.REVOKED, Instant.EPOCH, FAR_FUTURE)
    }

    private fun rateLimit(action: String, userId: UUID) {
        val rule = props.rule(action)
        if (!rateLimiter.tryAcquire("$action:$userId", RateLimiter.Rule(rule.limit, rule.window))) {
            throw RateLimitedException(60)
        }
    }

    private fun expiryFrom(now: Instant, minutes: Int): Instant = now.plus(Duration.ofMinutes(minutes.toLong()))

    private fun audit(principal: AuthPrincipal, eventType: String, invite: Invite, metadata: Map<String, Any?>) {
        auditLog.record(
            organizationId = principal.organizationId,
            eventType = eventType,
            actorUserId = principal.userId,
            targetType = "INVITE",
            targetId = invite.id,
            metadata = metadata,
        )
    }

    private fun Invite.toResponse(code: String, branchId: UUID) = InviteResponse(
        id = id!!,
        code = code,
        role = role,
        branchId = branchId,
        unitId = unitId,
        expiresAt = expiresAt,
        createdAt = createdAt,
    )
}
