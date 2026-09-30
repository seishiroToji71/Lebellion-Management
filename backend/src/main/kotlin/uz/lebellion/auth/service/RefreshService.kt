package uz.lebellion.auth.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.auth.config.AuthProperties
import uz.lebellion.auth.domain.RefreshToken
import uz.lebellion.auth.domain.RevocationReason
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.repo.RefreshTokenRepository
import uz.lebellion.auth.token.TokenHasher
import uz.lebellion.auth.web.AuthResponse
import uz.lebellion.auth.web.InvalidTokenException
import java.time.Clock

/**
 * Refresh-token rotation with theft detection, plus logout. A login forms a *family* (rotation chain);
 * only one token per family is live at a time (enforced by a partial-unique index).
 *
 * Order of checks on refresh (device binding first, deliberately independent of the grace window):
 *  1. unknown token hash                         → 401
 *  2. device_id != the family's bound device     → theft (DEVICE_MISMATCH), revoke family, 401
 *  3. user missing / deactivated                 → 401
 *  4. token already revoked                      → dead family; revoke any live remnant, 401
 *  5. token rotated & within grace (same device) → honest retry: rotate the family's live token
 *  6. token rotated & outside grace              → theft (TOKEN_REUSE), revoke family, 401
 *  7. token live but expired (sliding/absolute)  → revoke (EXPIRED), 401
 *  8. token live                                 → rotate, return a fresh pair
 *
 * `noRollbackFor = InvalidTokenException` so the revocation + audit rows written on the theft/expiry
 * paths COMMIT even though the request ends in a 401.
 */
@Service
class RefreshService(
    private val refreshTokens: RefreshTokenRepository,
    private val users: AppUserRepository,
    private val tokenIssuer: TokenIssuer,
    private val responses: AuthResponseFactory,
    private val audit: AuditLogRecorder,
    private val hasher: TokenHasher,
    private val props: AuthProperties,
    private val clock: Clock,
) {
    @Transactional(noRollbackFor = [InvalidTokenException::class])
    fun refresh(rawToken: String, deviceId: String): AuthResponse {
        val token = refreshTokens.findByTokenHashForUpdate(hasher.sha256Hex(rawToken))
            ?: throw InvalidTokenException()
        val now = clock.instant()

        // (2) Device binding — checked first, independent of grace/state: a mismatch is always theft.
        if (token.deviceId != deviceId) {
            refreshTokens.revokeAllActiveForFamily(token.familyId, now, RevocationReason.DEVICE_MISMATCH)
            recordTheft(token, RevocationReason.DEVICE_MISMATCH)
            throw InvalidTokenException()
        }

        // (3) User must still exist and be active (immediate lockout on deactivation).
        val user = users.findById(token.userId).orElse(null)
        if (user == null || !user.isActive) throw InvalidTokenException()

        // (4) Already-revoked token: the family is dead (logout/theft/password reset). Replaying it is
        //     benign if nothing is left live; if a live remnant somehow exists, kill it and flag theft.
        if (token.revokedAt != null) {
            val killed = refreshTokens.revokeAllActiveForFamily(token.familyId, now, RevocationReason.TOKEN_REUSE)
            if (killed > 0) recordTheft(token, RevocationReason.TOKEN_REUSE)
            throw InvalidTokenException()
        }

        val rotatedAt = token.rotatedAt
        if (rotatedAt != null) {
            if (!now.isAfter(rotatedAt.plus(props.refresh.grace))) {
                // (5) Grace: honest offline retry on the SAME device. We cannot re-hand the successor
                //     (only its hash is stored), so we rotate the family's current live token instead.
                val live = refreshTokens.findLiveByFamilyForUpdate(token.familyId) ?: throw InvalidTokenException()
                return responses.authResponse(tokenIssuer.rotate(live, user, deviceId), user)
            }
            // (6) Reuse of a rotated token past the grace window = classic replay ⇒ revoke the family.
            val killed = refreshTokens.revokeAllActiveForFamily(token.familyId, now, RevocationReason.TOKEN_REUSE)
            if (killed > 0) recordTheft(token, RevocationReason.TOKEN_REUSE)
            throw InvalidTokenException()
        }

        // (7) Live token — enforce sliding inactivity and the absolute cap before rotating.
        val absolute = token.absoluteExpiresAt
        if (!now.isBefore(token.expiresAt) || (absolute != null && !now.isBefore(absolute))) {
            refreshTokens.revokeAllActiveForFamily(token.familyId, now, RevocationReason.EXPIRED)
            throw InvalidTokenException()
        }

        // (8) Happy path.
        return responses.authResponse(tokenIssuer.rotate(token, user, deviceId), user)
    }

    /**
     * Revokes the presented token's whole family. Idempotent: an unknown token or an already-dead family
     * is a silent no-op (the controller always answers 204). Device is not checked — logging out a session
     * you hold the token for is safe from any device.
     */
    @Transactional
    fun logout(rawToken: String, deviceId: String) {
        val token = refreshTokens.findByTokenHashForUpdate(hasher.sha256Hex(rawToken)) ?: return
        val killed = refreshTokens.revokeAllActiveForFamily(token.familyId, clock.instant(), RevocationReason.LOGOUT)
        if (killed > 0) {
            audit.record(
                organizationId = token.organizationId,
                eventType = "LOGOUT",
                actorUserId = token.userId,
                targetType = "REFRESH_FAMILY",
                targetId = token.familyId,
                metadata = mapOf("deviceId" to deviceId),
            )
        }
    }

    private fun recordTheft(token: RefreshToken, reason: String) {
        audit.record(
            organizationId = token.organizationId,
            eventType = "REFRESH_THEFT_DETECTED",
            actorUserId = token.userId,
            targetType = "REFRESH_FAMILY",
            targetId = token.familyId,
            metadata = mapOf("reason" to reason, "deviceId" to token.deviceId),
        )
    }
}
