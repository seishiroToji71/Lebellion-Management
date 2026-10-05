package uz.lebellion.auth.security

import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.stereotype.Component
import uz.lebellion.auth.repo.AppUserRepository
import java.util.UUID

/**
 * Turns a validated JWT into an Authentication, re-reading the user FROM THE DB on EVERY request
 * (no cache) so that deactivation (is_active=false) or a token_version bump revokes access
 * immediately on the next call.
 */
@Component
class DbBackedJwtAuthenticationConverter(
    private val users: AppUserRepository,
) : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val userId = try {
            UUID.fromString(jwt.subject)
        } catch (_: Exception) {
            throw InvalidBearerTokenException("malformed subject")
        }
        val user = users.findById(userId).orElseThrow { InvalidBearerTokenException("unknown subject") }
        if (!user.isActive) throw InvalidBearerTokenException("user is deactivated")

        val tokenVersion = (jwt.claims["token_version"] as? Number)?.toInt()
            ?: throw InvalidBearerTokenException("missing token_version")
        if (tokenVersion != user.tokenVersion) throw InvalidBearerTokenException("token_version mismatch")

        // org_id / role must be present AND consistent with the DB. Authorization always uses the DB
        // values (single source of truth), but a token whose org_id/role claim is missing or tampered
        // is rejected outright — a forged or stale claim can never cross tenants or escalate a role.
        val orgClaim = jwt.getClaimAsString("org_id") ?: throw InvalidBearerTokenException("missing org_id")
        if (orgClaim != user.organizationId.toString()) throw InvalidBearerTokenException("org_id mismatch")
        val roleClaim = jwt.getClaimAsString("role") ?: throw InvalidBearerTokenException("missing role")
        if (roleClaim != user.role.name) throw InvalidBearerTokenException("role mismatch")

        val principal = AuthPrincipal(
            userId = user.id!!,
            organizationId = user.organizationId,
            role = user.role,
            branchId = user.branchId,
            deviceId = jwt.getClaimAsString("device_id"),
            mustChangePassword = user.mustChangePassword,
        )
        return AuthAuthentication(principal, listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")))
    }
}
