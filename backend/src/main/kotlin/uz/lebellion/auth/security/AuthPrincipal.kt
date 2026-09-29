package uz.lebellion.auth.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import uz.lebellion.auth.domain.Role
import java.util.UUID

/** Authenticated caller, resolved per request from the JWT + a fresh DB read. */
data class AuthPrincipal(
    val userId: UUID,
    val organizationId: UUID,
    val role: Role,
    val deviceId: String?,
)

class AuthAuthentication(
    private val principal: AuthPrincipal,
    authorities: Collection<GrantedAuthority>,
) : AbstractAuthenticationToken(authorities) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""
    override fun getPrincipal(): AuthPrincipal = principal
    override fun getName(): String = principal.userId.toString()
}
