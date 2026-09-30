package uz.lebellion.auth.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import java.util.concurrent.TimeUnit

/**
 * The 30s grace window: re-presenting a just-rotated token on the SAME device is an honest offline
 * retry, not theft — it returns a fresh valid pair and leaves the family alive. Grace is set generously
 * so the retry is comfortably inside the window without timing flakiness.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
        "lebellion.auth.refresh.grace=PT30S",
    ],
)
@Testcontainers
class RefreshGraceIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var mapper: ObjectMapper

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
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    private fun headers(device: String = "device-1") = mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to device)

    private fun registerFounder(): Map<String, Any?> {
        val res = post(
            "/api/v1/auth/register",
            mapOf(
                "organizationName" to "Org-${UUID.randomUUID()}",
                "fullName" to "The Founder",
                "email" to "founder-${UUID.randomUUID()}@example.com",
                "password" to "sup3rsecret!",
            ),
            headers(),
        )
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())
    }

    private fun refresh(token: String, device: String = "device-1") =
        post("/api/v1/auth/refresh", mapOf("refreshToken" to token), headers(device))

    @Test
    fun `re-presenting a just-rotated token within grace succeeds and keeps the family alive`() {
        val rt0 = registerFounder()["refreshToken"] as String

        val rt1 = parse(refresh(rt0).body())["refreshToken"] as String // rt0 -> rt1 (rt0 rotated)

        // honest retry of rt0 within grace on the same device: NOT theft — a fresh token is returned
        val retry = refresh(rt0)
        assertEquals(200, retry.statusCode(), retry.body())
        val rt2 = parse(retry.body())["refreshToken"] as String
        assertNotEquals(rt1, rt2)

        // the family is still alive: the newest token continues to rotate
        assertEquals(200, refresh(rt2).statusCode())
    }

    @Test
    fun `two concurrent refreshes with the same live token resolve cleanly, never a 500`() {
        val rt0 = registerFounder()["refreshToken"] as String
        val payload = mapper.writeValueAsString(mapOf("refreshToken" to rt0))

        fun fire() = http.sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/api/v1/auth/refresh"))
                .header("Content-Type", "application/json")
                .header("X-App-Version", "1.4.0")
                .header("X-Device-Id", "device-1")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        // Fire both before joining either, so they contend on the same row lock.
        val f1 = fire()
        val f2 = fire()
        val r1 = f1.get(15, TimeUnit.SECONDS)
        val r2 = f2.get(15, TimeUnit.SECONDS)
        val results = listOf(r1, r2)
        val statuses = results.map { it.statusCode() }

        // The row lock + grace reconciliation serialize the race; a stray partial-unique collision would
        // surface as a clean 409. Neither must ever be a 5xx.
        assertTrue(statuses.all { it == 200 || it == 409 }, "unexpected statuses $statuses: ${r1.body()} | ${r2.body()}")
        assertTrue(statuses.contains(200), "at least one concurrent refresh must succeed: $statuses")

        // Crucially the race was NOT misclassified as theft: the family is alive, so a token issued by a
        // winning request still rotates.
        val issued = results.first { it.statusCode() == 200 }.let { parse(it.body())["refreshToken"] as String }
        assertEquals(200, refresh(issued).statusCode(), "family should remain usable after a parallel refresh")
    }

    @Test
    fun `device mismatch within grace is still theft`() {
        val rt0 = registerFounder()["refreshToken"] as String
        parse(refresh(rt0).body()) // rotate once so rt0 is now within-grace rotated

        // even inside the grace window, a different device is theft, not an honest retry
        val theft = refresh(rt0, device = "device-2")
        assertEquals(401, theft.statusCode(), theft.body())
        assertEquals("INVALID_TOKEN", parse(theft.body())["code"])
    }
}
