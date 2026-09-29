package uz.lebellion.auth.service

import org.springframework.stereotype.Component
import uz.lebellion.auth.config.AuthProperties
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.domain.RefreshToken
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.RefreshTokenRepository
import uz.lebellion.auth.token.JwtService
import uz.lebellion.auth.token.SecureCodeGenerator
import uz.lebellion.auth.token.TokenHasher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Issues an access token + a persisted refresh token. Refresh lifetimes are role-specific:
 * EMPLOYEE = 60d sliding (no absolute cap); FOUNDER/BRANCH_MANAGER = 30d sliding + 90d absolute cap.
 */
@Component
class TokenIssuer(
    private val jwtService: JwtService,
    private val codes: SecureCodeGenerator,
    private val hasher: TokenHasher,
    private val refreshTokens: RefreshTokenRepository,
    private val props: AuthProperties,
    private val clock: Clock,
) {
    data class Issued(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

    /** Starts a brand-new session (new refresh family) for [user] bound to [deviceId]. */
    fun issueNewSession(user: AppUser, deviceId: String): Issued {
        val now = clock.instant()
        val raw = codes.opaqueToken()
        val (sliding, absolute) = ttls(user.role, now)
        refreshTokens.save(
            RefreshToken(
                organizationId = user.organizationId,
                userId = user.id!!,
                familyId = UUID.randomUUID(),
                tokenHash = hasher.sha256Hex(raw),
                deviceId = deviceId,
                issuedAt = now,
                expiresAt = now.plus(sliding),
                absoluteExpiresAt = absolute,
            ),
        )
        val access = jwtService.issueAccessToken(
            userId = user.id!!,
            organizationId = user.organizationId,
            role = user.role.name,
            tokenVersion = user.tokenVersion,
            deviceId = deviceId,
            now = now,
        )
        return Issued(access, raw, props.jwt.accessTtl.seconds)
    }

    private fun ttls(role: Role, now: Instant): Pair<Duration, Instant?> = when (role) {
        Role.EMPLOYEE -> props.refresh.employeeSliding to null
        else -> props.refresh.managerSliding to now.plus(props.refresh.managerAbsolute)
    }
}
