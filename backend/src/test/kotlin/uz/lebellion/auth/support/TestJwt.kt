package uz.lebellion.auth.support

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * Mints access tokens for security tests — valid ones with tweaked/missing claims (via the app's own
 * [JwtEncoder]) and ones signed with a foreign key (to simulate a forged signature).
 */
object TestJwt {

    /** An encoder using a DIFFERENT HS256 secret than the app — tokens it signs must be rejected. */
    fun foreignEncoder(): JwtEncoder {
        val key = SecretKeySpec(
            "a-totally-different-secret-key-0123456789abcdef".toByteArray(StandardCharsets.UTF_8),
            "HmacSHA256",
        )
        return NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))
    }

    /**
     * Builds an HS256 access token. Any claim passed as null is omitted, so callers can exercise the
     * "missing claim" cases. [expiresAt] defaults to the future; pass a past instant for the expiry case.
     */
    fun mint(
        encoder: JwtEncoder,
        subject: String,
        orgId: String?,
        role: String?,
        tokenVersion: Int?,
        deviceId: String = "device-1",
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant = Instant.now().plusSeconds(600),
    ): String {
        val claims = JwtClaimsSet.builder()
            .subject(subject)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("device_id", deviceId)
        if (orgId != null) claims.claim("org_id", orgId)
        if (role != null) claims.claim("role", role)
        if (tokenVersion != null) claims.claim("token_version", tokenVersion)
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).tokenValue
    }
}
