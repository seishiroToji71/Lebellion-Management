package uz.lebellion.auth.token

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import uz.lebellion.auth.config.AuthProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class JwtServiceTest {

    private val secretKey = SecretKeySpec(
        "test-secret-test-secret-test-secret-0123456789".toByteArray(),
        "HmacSHA256",
    )
    private val encoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(secretKey))
    private val decoder: JwtDecoder =
        NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build()

    private fun service(accessTtl: Duration) =
        JwtService(encoder, AuthProperties(jwt = AuthProperties.Jwt(secret = "unused-here", accessTtl = accessTtl)))

    @Test
    fun `issued token carries expected claims and verifies`() {
        val userId = UUID.randomUUID()
        val orgId = UUID.randomUUID()
        val token = service(Duration.ofMinutes(15)).issueAccessToken(
            userId = userId,
            organizationId = orgId,
            role = "FOUNDER",
            tokenVersion = 3,
            deviceId = "device-abc",
        )

        val jwt = decoder.decode(token)
        assertEquals(userId.toString(), jwt.subject)
        assertEquals(orgId.toString(), jwt.getClaimAsString("org_id"))
        assertEquals("FOUNDER", jwt.getClaimAsString("role"))
        // Numeric JWT claims round-trip as Long via JSON.
        assertEquals(3, (jwt.getClaims()["token_version"] as Number).toInt())
        assertEquals("device-abc", jwt.getClaimAsString("device_id"))
    }

    @Test
    fun `tampered token is rejected`() {
        val token = service(Duration.ofMinutes(15)).issueAccessToken(
            UUID.randomUUID(), UUID.randomUUID(), "EMPLOYEE", 0, "d",
        )
        // Flip a character in the payload segment.
        val parts = token.split(".")
        val tampered = parts[0] + "." + parts[1].dropLast(1) + (if (parts[1].last() == 'A') 'B' else 'A') + "." + parts[2]
        assertThrows(JwtException::class.java) { decoder.decode(tampered) }
    }

    @Test
    fun `expired token is rejected`() {
        // Issued an hour ago with a 15m TTL => expiresAt well beyond the decoder's 60s clock skew.
        val token = service(Duration.ofMinutes(15)).issueAccessToken(
            UUID.randomUUID(), UUID.randomUUID(), "BRANCH_MANAGER", 1, "d",
            now = Instant.now().minusSeconds(3600),
        )
        assertThrows(JwtException::class.java) { decoder.decode(token) }
    }
}
