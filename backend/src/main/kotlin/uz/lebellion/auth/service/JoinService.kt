package uz.lebellion.auth.service

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.auth.config.AuthProperties
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.domain.Invite
import uz.lebellion.auth.domain.InviteStatus
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.ratelimit.RateLimiter
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.repo.InviteRepository
import uz.lebellion.auth.repo.RefreshTokenRepository
import uz.lebellion.auth.token.HmacCodec
import uz.lebellion.auth.web.AuthResponse
import uz.lebellion.auth.web.InvalidCredentialsException
import uz.lebellion.auth.web.InviteAlreadyUsedException
import uz.lebellion.auth.web.JoinRequest
import uz.lebellion.auth.web.RateLimitedException
import uz.lebellion.auth.web.RequestValidationException
import java.time.Clock
import java.time.Instant

@Service
class JoinService(
    private val invites: InviteRepository,
    private val users: AppUserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val encoder: PasswordEncoder,
    private val tokenIssuer: TokenIssuer,
    private val rateLimiter: RateLimiter,
    private val responses: AuthResponseFactory,
    private val audit: AuditLogRecorder,
    private val props: AuthProperties,
    private val clock: Clock,
    @Qualifier("inviteHmacCodec") private val inviteHmac: HmacCodec,
) {
    private data class Consumed(val user: AppUser, val eventType: String, val metadata: Map<String, Any?>)

    @Transactional
    fun join(req: JoinRequest, deviceId: String, clientIp: String): AuthResponse {
        val rule = props.rule("join-ip")
        if (!rateLimiter.tryAcquireAll(listOf(RateLimiter.Check("join-ip:$clientIp", RateLimiter.Rule(rule.limit, rule.window))))) {
            throw RateLimitedException(60)
        }

        val hmac = inviteHmac.hmacHex(req.inviteCode.trim())
        val invite = invites.findByCodeHmacForUpdate(hmac) ?: throw InvalidCredentialsException()
        val now = clock.instant()

        if (invite.status != InviteStatus.PENDING || invite.expiresAt.isBefore(now)) {
            // Deterministic: the very device that consumed the code gets a clear 409; everyone else
            // gets the enumeration-safe 401 (same as unknown/expired/revoked).
            val usedBy = invite.usedBy
            if (invite.status == InviteStatus.USED && usedBy != null &&
                refreshTokens.existsByUserIdAndDeviceId(usedBy, deviceId)
            ) {
                throw InviteAlreadyUsedException()
            }
            throw InvalidCredentialsException()
        }

        val consumed = when {
            invite.targetEmployeeId != null -> consumeRecovery(invite, req, now)
            invite.role == Role.EMPLOYEE -> consumeEmployee(invite, req)
            invite.role == Role.BRANCH_MANAGER -> consumeManager(invite, req)
            else -> throw InvalidCredentialsException()
        }

        // Mark USED atomically with user creation + first token (single transaction; the pessimistic
        // lock serializes concurrent joins). Any failure above rolled back and left the code PENDING.
        invite.status = InviteStatus.USED
        invite.usedBy = consumed.user.id
        invite.usedAt = now
        invites.save(invite)

        val issued = tokenIssuer.issueNewSession(consumed.user, deviceId)
        // Flush JPA writes (new user + refresh + invite update) so the raw-JDBC audit insert below
        // sees them — its actor_user_id FK references the just-created user.
        users.flush()
        audit.record(
            organizationId = invite.organizationId,
            eventType = consumed.eventType,
            actorUserId = consumed.user.id,
            targetType = "USER",
            targetId = consumed.user.id,
            metadata = consumed.metadata + ("inviteId" to invite.id.toString()),
        )
        return responses.authResponse(issued, consumed.user)
    }

    private fun consumeEmployee(invite: Invite, req: JoinRequest): Consumed {
        if (!req.password.isNullOrBlank()) throw RequestValidationException("password is not allowed for an employee invite")
        val user = users.save(
            AppUser(
                organizationId = invite.organizationId,
                name = req.fullName,
                role = Role.EMPLOYEE,
                unitId = invite.unitId,
            ),
        )
        return Consumed(user, "EMPLOYEE_JOINED", mapOf("role" to Role.EMPLOYEE.name))
    }

    private fun consumeManager(invite: Invite, req: JoinRequest): Consumed {
        val password = req.password
        if (password.isNullOrBlank()) throw RequestValidationException("password is required for a branch-manager invite")
        val email = invite.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val phone = invite.phone?.let(::normalizePhone)?.takeIf { it.isNotEmpty() }
        if (email == null && phone == null) throw RequestValidationException("branch-manager invite is missing a login contact")
        val user = users.save(
            AppUser(
                organizationId = invite.organizationId,
                name = req.fullName,
                role = Role.BRANCH_MANAGER,
                branchId = invite.branchId,
                email = email,
                phone = phone,
                passwordHash = encoder.encode(password),
            ),
        )
        return Consumed(user, "MANAGER_JOINED", mapOf("role" to Role.BRANCH_MANAGER.name, "branchId" to invite.branchId.toString()))
    }

    private fun consumeRecovery(invite: Invite, req: JoinRequest, now: Instant): Consumed {
        if (!req.password.isNullOrBlank()) throw RequestValidationException("password is not allowed for a recovery invite")
        val user = users.findByIdAndOrganizationId(invite.targetEmployeeId!!, invite.organizationId)
            ?: throw InvalidCredentialsException()
        if (!user.isActive) throw InvalidCredentialsException()
        // Device change / admin reset: drop every previous session for this user.
        refreshTokens.revokeAllActiveForUser(user.id!!, now, "RECOVERY")
        return Consumed(user, "RECOVERY_JOIN", mapOf("recovery" to true))
    }

    private fun normalizePhone(raw: String): String = raw.trim().replace(Regex("[\\s()\\-]"), "")
}
