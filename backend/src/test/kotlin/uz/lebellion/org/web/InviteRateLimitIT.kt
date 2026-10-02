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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * Minting invite codes is capped per caller: issuing/reissuing a secret must not be unbounded even for
 * an authorized FOUNDER, so a compromised account cannot churn out codes. `invite-create.limit` is
 * pinned to 2 here so the third create in the window is refused with 429.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
        "lebellion.auth.rate-limit.invite-create.limit=2",
        "lebellion.auth.rate-limit.invite-create.window=PT1M",
    ],
)
@Testcontainers
class InviteRateLimitIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun post(path: String, body: Map<String, Any?>, headers: Map<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String) = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    private fun bearer(token: String) = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")

    @Test
    fun `invite creation is rate limited per caller`() {
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
        val token = parse(reg.body())["accessToken"] as String

        val branch = post("/api/v1/branches", mapOf("name" to "HQ"), bearer(token))
        val branchId = parse(branch.body())["id"] as String
        val unit = post("/api/v1/branches/$branchId/units", mapOf("name" to "Kitchen"), bearer(token))
        val unitId = parse(unit.body())["id"] as String

        val body = mapOf("role" to "EMPLOYEE", "unitId" to unitId)
        assertEquals(201, post("/api/v1/invites", body, bearer(token)).statusCode())
        assertEquals(201, post("/api/v1/invites", body, bearer(token)).statusCode())

        val limited = post("/api/v1/invites", body, bearer(token))
        assertEquals(429, limited.statusCode(), limited.body())
        assertEquals("RATE_LIMITED", parse(limited.body())["code"])
    }
}
