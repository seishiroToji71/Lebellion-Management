package uz.lebellion.auth.token

import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import uz.lebellion.auth.config.AuthProperties
import java.time.Instant
import java.util.UUID

/**
 * Issues short-lived HS256 access tokens. The `token_version` claim lets us revoke access
 * immediately (bump the user's version); `device_id` binds the token to one device.
 */
@Service
class JwtService(
    private val encoder: JwtEncoder,
    private val props: AuthProperties,
) {

    fun issueAccessToken(
        userId: UUID,
        organizationId: UUID,
        role: String,
        tokenVersion: Int,
        deviceId: String,
        now: Instant = Instant.now(),
    ): String {
        val claims = JwtClaimsSet.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(props.jwt.accessTtl))
            .claim("org_id", organizationId.toString())
            .claim("role", role)
            .claim("token_version", tokenVersion)
            .claim("device_id", deviceId)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }
}
