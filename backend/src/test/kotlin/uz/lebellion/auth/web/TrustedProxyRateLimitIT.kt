package uz.lebellion.auth.web

import org.junit.jupiter.api.Assertions.assertFalse
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

/**
 * Per-IP rate limiting must key off the IP our single trusted proxy (Caddy) appends to
 * X-Forwarded-For — NOT anything the client stuffs to the left of it. Otherwise an attacker rotates
 * X-Forwarded-For and gets a fresh bucket per request, defeating the limit.
 *
 * trusted-proxy-count = 1, so the rightmost XFF entry is the trusted one. We isolate the IP dimension
 * by leaving login-user effectively unlimited and hammering the same (unknown) login.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.trusted-proxy-count=1",
        "lebellion.auth.rate-limit.login-ip.limit=3",
        "lebellion.auth.rate-limit.login-ip.window=PT1M",
        "lebellion.auth.rate-limit.login-user.limit=100000",
        "lebellion.auth.rate-limit.login-user.window=PT1M",
    ],
)
@Testcontainers
class TrustedProxyRateLimitIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun login(forwardedFor: String, login: String): Int {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .header("X-App-Version", "1.4.0")
            .header("X-Device-Id", "device-1")
            .header("X-Forwarded-For", forwardedFor)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("login" to login, "password" to "whatever-123"))))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    @Test
    fun `spoofed left X-Forwarded-For entries cannot bypass the per-IP login limit`() {
        val account = "ghost-${UUID.randomUUID()}@x.com"
        val realClient = "203.0.113.7" // what our single trusted proxy (Caddy) appends — always rightmost

        // Every request ends in the same trusted IP, but the attacker prepends a DIFFERENT number of
        // fake entries on the left each time. The right-anchored resolver must ignore all of them, so
        // all six share one bucket and the limit (3) trips.
        val spoofs = listOf(
            "$realClient",
            "10.0.0.1, $realClient",
            "9.9.9.9, 8.8.8.8, $realClient",
            "1.2.3.4, 5.6.7.8, 9.10.11.12, $realClient",
            "$realClient",
            "42.42.42.42, $realClient",
        )
        val statuses = spoofs.map { login(it, account) }

        // First few are ordinary 401s; once the per-IP bucket is exhausted we must see 429.
        assertTrue(statuses.contains(429), "rate limit never triggered — XFF spoofing bypassed it: $statuses")
        assertFalse(statuses.any { it >= 500 }, "no server error expected: $statuses")
    }

    @Test
    fun `distinct trusted client IPs are limited independently`() {
        val account = "ghost-${UUID.randomUUID()}@x.com"
        // Each request presents a different rightmost (trusted) IP → its own bucket → stays 401.
        val statuses = (1..3).map { i -> login("198.51.100.$i", account) }
        assertFalse(statuses.contains(429), "independent client IPs must not share a bucket: $statuses")
        assertTrue(statuses.all { it == 401 }, "expected plain 401s for distinct IPs: $statuses")
    }
}
