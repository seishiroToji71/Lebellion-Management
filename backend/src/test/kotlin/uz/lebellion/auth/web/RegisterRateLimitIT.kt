package uz.lebellion.auth.web

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

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=2",
    ],
)
@Testcontainers
class RegisterRateLimitIT {

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

    private fun register(): HttpResponse<String> {
        val body = mapOf(
            "organizationName" to "Org-${UUID.randomUUID()}",
            "fullName" to "Founder",
            "email" to "f-${UUID.randomUUID()}@x.com",
            "password" to "founder-pass-1",
        )
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/auth/register"))
            .header("Content-Type", "application/json")
            .header("X-App-Version", "1.4.0")
            .header("X-Device-Id", "device-1")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `register is rate limited per client IP`() {
        // limit = 2 within the window; the third attempt from the same IP is throttled.
        assertEquals(201, register().statusCode())
        assertEquals(201, register().statusCode())

        val throttled = register()
        assertEquals(429, throttled.statusCode(), throttled.body())
        assertEquals("RATE_LIMITED", mapper.readValue(throttled.body(), Map::class.java)["code"])
    }
}
