package uz.lebellion.auth.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * Refresh rotation + theft detection. Grace is set to zero so an *immediate* reuse already counts as
 * "outside the window" — that lets us exercise reuse→revoke and device-mismatch without sleeping.
 * The within-grace success path lives in [RefreshGraceIT].
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
        "lebellion.auth.refresh.grace=PT0S",
    ],
)
@Testcontainers
class RefreshIT {

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

    private fun get(path: String, headers: Map<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path")).GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    private fun headers(device: String = "device-1") = mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to device)

    private fun registerFounder(device: String = "device-1"): Map<String, Any?> {
        val res = post(
            "/api/v1/auth/register",
            mapOf(
                "organizationName" to "Org-${UUID.randomUUID()}",
                "fullName" to "The Founder",
                "email" to "founder-${UUID.randomUUID()}@example.com",
                "password" to "sup3rsecret!",
            ),
            headers(device),
        )
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())
    }

    private fun refresh(token: String, device: String = "device-1") =
        post("/api/v1/auth/refresh", mapOf("refreshToken" to token), headers(device))

    // --- happy path ------------------------------------------------------

    @Test
    fun `refresh rotates and returns a new usable pair`() {
        val rt0 = registerFounder()["refreshToken"] as String

        val res = refresh(rt0)
        assertEquals(200, res.statusCode(), res.body())
        val body = parse(res.body())
        val rt1 = body["refreshToken"] as String
        val access1 = body["accessToken"] as String
        assertNotEquals(rt0, rt1)
        assertNotNull(access1)

        // the freshly issued access token works
        val me = get("/api/v1/me", mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $access1"))
        assertEquals(200, me.statusCode(), me.body())
        // and the new refresh token rotates again
        assertEquals(200, refresh(rt1).statusCode())
    }

    // --- reuse -> revoke (the core theft case) ---------------------------

    @Test
    fun `reusing a rotated token outside grace revokes the whole family`() {
        val rt0 = registerFounder()["refreshToken"] as String

        val rt1 = parse(refresh(rt0).body())["refreshToken"] as String // rt0 -> rt1 (rt0 now rotated)

        // replaying rt0 past the grace window is treated as theft
        val reuse = refresh(rt0)
        assertEquals(401, reuse.statusCode(), reuse.body())
        assertEquals("INVALID_TOKEN", parse(reuse.body())["code"])

        // the family is revoked: the once-valid successor rt1 no longer works either
        val successor = refresh(rt1)
        assertEquals(401, successor.statusCode(), successor.body())
        assertEquals("INVALID_TOKEN", parse(successor.body())["code"])
    }

    // --- device mismatch = theft, regardless of the grace window ---------

    @Test
    fun `refresh from a different device revokes the family even for a live token`() {
        val rt0 = registerFounder(device = "device-1")["refreshToken"] as String

        val wrongDevice = refresh(rt0, device = "device-2")
        assertEquals(401, wrongDevice.statusCode(), wrongDevice.body())
        assertEquals("INVALID_TOKEN", parse(wrongDevice.body())["code"])

        // family revoked: even the correct device can no longer use the (still-unrotated) token
        val correctDevice = refresh(rt0, device = "device-1")
        assertEquals(401, correctDevice.statusCode(), correctDevice.body())
        assertEquals("INVALID_TOKEN", parse(correctDevice.body())["code"])
    }

    // --- logout ----------------------------------------------------------

    @Test
    fun `logout revokes the session and is idempotent`() {
        val rt0 = registerFounder()["refreshToken"] as String

        val out = post("/api/v1/auth/logout", mapOf("refreshToken" to rt0), headers())
        assertEquals(204, out.statusCode(), out.body())

        // the session is gone
        val afterLogout = refresh(rt0)
        assertEquals(401, afterLogout.statusCode(), afterLogout.body())
        assertEquals("INVALID_TOKEN", parse(afterLogout.body())["code"])

        // logging out again is a silent no-op (still 204, no theft escalation)
        val again = post("/api/v1/auth/logout", mapOf("refreshToken" to rt0), headers())
        assertEquals(204, again.statusCode(), again.body())
    }

    // --- misc ------------------------------------------------------------

    @Test
    fun `refresh with an unknown token is 401 INVALID_TOKEN`() {
        val res = refresh("this-token-was-never-issued")
        assertEquals(401, res.statusCode(), res.body())
        assertEquals("INVALID_TOKEN", parse(res.body())["code"])
    }

    @Test
    fun `refresh without X-Device-Id is 400 DEVICE_ID_REQUIRED`() {
        val rt0 = registerFounder()["refreshToken"] as String
        val res = post("/api/v1/auth/refresh", mapOf("refreshToken" to rt0), mapOf("X-App-Version" to "1.4.0"))
        assertEquals(400, res.statusCode(), res.body())
        assertEquals("DEVICE_ID_REQUIRED", parse(res.body())["code"])
    }
}
