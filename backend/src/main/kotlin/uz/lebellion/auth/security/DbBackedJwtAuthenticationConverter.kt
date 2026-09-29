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

        val principal = AuthPrincipal(
            userId = user.id!!,
            organizationId = user.organizationId,
            role = user.role,
            deviceId = jwt.getClaimAsString("device_id"),
        )
        return AuthAuthentication(principal, listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")))
    }
}
