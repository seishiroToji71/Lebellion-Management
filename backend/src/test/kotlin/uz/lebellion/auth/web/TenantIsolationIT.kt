package uz.lebellion.auth.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import java.util.UUID

/**
 * Tenant isolation at the surface that exists today (/api/v1/me). A token is bound to its own user and
 * organization; it cannot be pointed at another tenant, and a validly-signed token that SPOOFS the
 * org_id (or role) claim is rejected rather than honoured — the DB is the source of truth.
 *
 * Endpoint-level isolation for /employees and /invites is a first-class requirement of block (г) and is
 * delivered together with those endpoints (they don't exist yet).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
    ],
)
@Testcontainers
class TenantIsolationIT {

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

    private data class Founder(val userId: String, val orgId: String, val accessToken: String)

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
        val body = mapper.readValue(res.body(), Map::class.java) as Map<String, Any?>
        val user = body["user"] as Map<*, *>
        return Founder(user["id"] as String, user["organizationId"] as String, body["accessToken"] as String)
    }

    private fun me(token: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/me"))
            .header("X-App-Version", "1.4.0")
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String) = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    @Test
    fun `each founder's token resolves only to their own organization`() {
        val a = registerFounder()
        val b = registerFounder()
        assertNotEquals(a.orgId, b.orgId)

        val meA = me(a.accessToken)
        assertEquals(200, meA.statusCode(), meA.body())
        assertEquals(a.orgId, parse(meA.body())["organizationId"])
        assertEquals(a.userId, parse(meA.body())["id"])

        val meB = me(b.accessToken)
        assertEquals(200, meB.statusCode(), meB.body())
        assertEquals(b.orgId, parse(meB.body())["organizationId"])
        // A's token never surfaces B's tenant, and vice versa.
        assertNotEquals(parse(meA.body())["organizationId"], parse(meB.body())["organizationId"])
    }

    @Test
    fun `a validly-signed token that spoofs another org's org_id is rejected`() {
        val a = registerFounder()
        val b = registerFounder()
        // Own user, but claim the OTHER organization — must not be able to act as tenant B.
        val spoofed = TestJwt.mint(appEncoder, subject = a.userId, orgId = b.orgId, role = "FOUNDER", tokenVersion = 0)
        assertEquals(401, me(spoofed).statusCode())
    }

    @Test
    fun `a validly-signed token that spoofs a different role is rejected`() {
        val a = registerFounder() // a FOUNDER
        val escalated = TestJwt.mint(appEncoder, subject = a.userId, orgId = a.orgId, role = "BRANCH_MANAGER", tokenVersion = 0)
        assertEquals(401, me(escalated).statusCode())
    }
}
