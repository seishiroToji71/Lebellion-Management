package uz.lebellion.auth.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import uz.lebellion.auth.support.TestJwt
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/**
 * A broken or forged access token must never authenticate: bad signature, expired, malformed, missing
 * bearer, unknown subject, and missing/mismatched token_version / org_id / role all yield 401 on /me.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
    ],
)
@Testcontainers
class AccessTokenSecurityIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var appEncoder: JwtEncoder

    private val http: HttpClient = HttpClient.newHttpClient()

    private data class Founder(val userId: String, val orgId: String)

    private fun registerFounder(): Founder {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/auth/register"))
            .header("Content-Type", "application/json")
            .header("X-App-Version", "1.4.0")
            .header("X-Device-Id", "device-1")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(
                        mapOf(
                            "organizationName" to "Org-${UUID.randomUUID()}",
                            "fullName" to "The Founder",
                            "email" to "founder-${UUID.randomUUID()}@example.com",
                            "password" to "sup3rsecret!",
                        ),
                    ),
                ),
            )
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(201, res.statusCode(), res.body())
        @Suppress("UNCHECKED_CAST")
        val user = (mapper.readValue(res.body(), Map::class.java) as Map<String, Any?>)["user"] as Map<*, *>
        return Founder(user["id"] as String, user["organizationId"] as String)
    }

    /** Calls /me with the given raw Authorization header value (or none), returns the status code. */
    private fun meStatus(authorization: String?): Int {
        val b = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/me"))
            .header("X-App-Version", "1.4.0")
            .GET()
        if (authorization != null) b.header("Authorization", authorization)
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun bearer(token: String) = meStatus("Bearer $token")

    // --- sanity ----------------------------------------------------------

    @Test
    fun `a well-formed token authenticates`() {
        val f = registerFounder()
        val token = TestJwt.mint(appEncoder, subject = f.userId, orgId = f.orgId, role = "FOUNDER", tokenVersion = 0)
        assertEquals(200, bearer(token))
    }

    // --- forged / broken -------------------------------------------------

    @Test
    fun `a token signed with a foreign key is 401`() {
        val f = registerFounder()
        val forged = TestJwt.mint(TestJwt.foreignEncoder(), subject = f.userId, orgId = f.orgId, role = "FOUNDER", tokenVersion = 0)
        assertEquals(401, bearer(forged))
    }

    @Test
    fun `an expired token is 401`() {
        val f = registerFounder()
        val expired = TestJwt.mint(
            appEncoder, subject = f.userId, orgId = f.orgId, role = "FOUNDER", tokenVersion = 0,
            issuedAt = Instant.now().minusSeconds(3600), expiresAt = Instant.now().minusSeconds(60),
        )
        assertEquals(401, bearer(expired))
    }

    @Test
    fun `a malformed bearer and a missing header are both 401`() {
        assertEquals(401, bearer("this.is.not-a-jwt"))
        assertEquals(401, meStatus(null))
    }

    @Test
    fun `an unknown subject is 401`() {
        val f = registerFounder()
        val ghost = TestJwt.mint(appEncoder, subject = UUID.randomUUID().toString(), orgId = f.orgId, role = "FOUNDER", tokenVersion = 0)
        assertEquals(401, bearer(ghost))
    }

    @Test
    fun `missing or wrong token_version is 401`() {
        val f = registerFounder()
        val missing = TestJwt.mint(appEncoder, subject = f.userId, orgId = f.orgId, role = "FOUNDER", tokenVersion = null)
        val wrong = TestJwt.mint(appEncoder, subject = f.userId, orgId = f.orgId, role = "FOUNDER", tokenVersion = 999)
        assertEquals(401, bearer(missing))
        assertEquals(401, bearer(wrong))
    }

    @Test
    fun `missing org_id or role claim is 401`() {
        val f = registerFounder()
        val noOrg = TestJwt.mint(appEncoder, subject = f.userId, orgId = null, role = "FOUNDER", tokenVersion = 0)
        val noRole = TestJwt.mint(appEncoder, subject = f.userId, orgId = f.orgId, role = null, tokenVersion = 0)
        assertEquals(401, bearer(noOrg))
        assertEquals(401, bearer(noRole))
    }
}
