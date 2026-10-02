package uz.lebellion.org.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.AppUserRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** Issuing a recovery invite mints a secret (and, for a manager, a temp password): capped per caller. */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
        "lebellion.auth.rate-limit.recovery-invite.limit=2",
        "lebellion.auth.rate-limit.recovery-invite.window=PT1M",
    ],
)
@Testcontainers
class RecoveryInviteRateLimitIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var users: AppUserRepository

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun post(path: String, body: Map<String, Any?>?, headers: Map<String, String>): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.ofString("{}")
        else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(publisher)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String) = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    @Test
    fun `recovery-invite is rate limited per caller`() {
        val reg = post(
            "/api/v1/auth/register",
            mapOf(
                "organizationName" to "Org-${UUID.randomUUID()}",
                "fullName" to "Founder",
                "email" to "f-${UUID.randomUUID()}@example.com",
                "password" to "sup3rsecret!",
            ),
            mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to "device-1"),
        )
        assertEquals(201, reg.statusCode(), reg.body())
        @Suppress("UNCHECKED_CAST")
        val user = parse(reg.body())["user"] as Map<String, Any?>
        val orgId = user["organizationId"] as String
        val token = parse(reg.body())["accessToken"] as String
        val bearer = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")

        // three unit-bound employees (EMPLOYEE shape requires a unit, and one PENDING recovery per
        // user is unique) — distinct targets isolate the per-caller rate limit as the only thing biting.
        val branchId = parse(post("/api/v1/branches", mapOf("name" to "HQ"), bearer).body())["id"] as String
        val unitId = parse(post("/api/v1/branches/$branchId/units", mapOf("name" to "Kitchen"), bearer).body())["id"] as String
        val targets = (1..3).map {
            users.save(AppUser(organizationId = UUID.fromString(orgId), name = "E$it", role = Role.EMPLOYEE, unitId = UUID.fromString(unitId))).id!!.toString()
        }

        assertEquals(201, post("/api/v1/employees/${targets[0]}/recovery-invite", null, bearer).statusCode())
        assertEquals(201, post("/api/v1/employees/${targets[1]}/recovery-invite", null, bearer).statusCode())

        val limited = post("/api/v1/employees/${targets[2]}/recovery-invite", null, bearer)
        assertEquals(429, limited.statusCode(), limited.body())
        assertEquals("RATE_LIMITED", parse(limited.body())["code"])
    }
}
