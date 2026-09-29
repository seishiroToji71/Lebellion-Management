package uz.lebellion.auth.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["lebellion.auth.registration-enabled=true"])
@Testcontainers
class AuthEndpointsIT {

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

    // --- helpers ---------------------------------------------------------

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

    @Suppress("UNCHECKED_CAST")
    private fun user(body: Map<String, Any?>): Map<String, Any?> = body["user"] as Map<String, Any?>

    private val appHeaders = mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to "device-1")

    private fun registerFounder(
        deviceId: String = "device-1",
        email: String = "founder-${UUID.randomUUID()}@example.com",
        password: String = "sup3rsecret!",
    ): Map<String, Any?> {
        val res = post(
            "/api/v1/auth/register",
            mapOf("organizationName" to "Org-${UUID.randomUUID()}", "fullName" to "The Founder", "email" to email, "password" to password),
            mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to deviceId),
        )
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())
    }

    // --- register --------------------------------------------------------

    @Test
    fun `register returns 201 with tokens and founder profile`() {
        val body = registerFounder()
        assertNotNull(body["accessToken"] as String)
        assertNotNull(body["refreshToken"] as String)
        assertEquals("Bearer", body["tokenType"])
        assertTrue((body["expiresIn"] as Number).toLong() > 0)
        assertEquals("FOUNDER", user(body)["role"])
        assertEquals(false, user(body)["mustChangePassword"])
    }

    @Test
    fun `register without X-Device-Id is 400 DEVICE_ID_REQUIRED`() {
        val res = post(
            "/api/v1/auth/register",
            mapOf("organizationName" to "Org-${UUID.randomUUID()}", "fullName" to "F", "email" to "a@b.com", "password" to "sup3rsecret!"),
            mapOf("X-App-Version" to "1.4.0"),
        )
        assertEquals(400, res.statusCode(), res.body())
        assertEquals("DEVICE_ID_REQUIRED", parse(res.body())["code"])
    }

    @Test
    fun `register without X-App-Version is 426`() {
        val res = post(
            "/api/v1/auth/register",
            mapOf("organizationName" to "Org-${UUID.randomUUID()}", "fullName" to "F", "email" to "a@b.com", "password" to "sup3rsecret!"),
            mapOf("X-Device-Id" to "device-1"),
        )
        assertEquals(426, res.statusCode(), res.body())
        assertEquals("UPGRADE_REQUIRED", parse(res.body())["code"])
    }

    @Test
    fun `register with duplicate organization name is 409`() {
        val orgName = "Dup-Org-${UUID.randomUUID()}"
        val first = post(
            "/api/v1/auth/register",
            mapOf("organizationName" to orgName, "fullName" to "F", "email" to "a-${UUID.randomUUID()}@b.com", "password" to "sup3rsecret!"),
            appHeaders,
        )
        assertEquals(201, first.statusCode(), first.body())
        val second = post(
            "/api/v1/auth/register",
            mapOf("organizationName" to orgName, "fullName" to "F", "email" to "b-${UUID.randomUUID()}@b.com", "password" to "sup3rsecret!"),
            appHeaders,
        )
        assertEquals(409, second.statusCode(), second.body())
        assertEquals("CONFLICT", parse(second.body())["code"])
    }

    // --- login -----------------------------------------------------------

    @Test
    fun `login succeeds and both wrong password and unknown login return identical 401`() {
        val email = "login-${UUID.randomUUID()}@example.com"
        registerFounder(email = email, password = "sup3rsecret!")

        val ok = post("/api/v1/auth/login", mapOf("login" to email, "password" to "sup3rsecret!"), appHeaders)
        assertEquals(200, ok.statusCode(), ok.body())
        assertNotNull(parse(ok.body())["accessToken"] as String)

        val wrongPw = post("/api/v1/auth/login", mapOf("login" to email, "password" to "wrong-password"), appHeaders)
        assertEquals(401, wrongPw.statusCode())
        assertEquals("INVALID_CREDENTIALS", parse(wrongPw.body())["code"])

        val unknown = post("/api/v1/auth/login", mapOf("login" to "nobody-${UUID.randomUUID()}@x.com", "password" to "whatever!!"), appHeaders)
        assertEquals(401, unknown.statusCode())
        assertEquals("INVALID_CREDENTIALS", parse(unknown.body())["code"])
    }

    // --- me --------------------------------------------------------------

    @Test
    fun `me returns profile with a valid token and 401 without one`() {
        val token = registerFounder()["accessToken"] as String

        val ok = get("/api/v1/me", mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token"))
        assertEquals(200, ok.statusCode(), ok.body())
        assertEquals("FOUNDER", parse(ok.body())["role"])

        val noAuth = get("/api/v1/me", mapOf("X-App-Version" to "1.4.0"))
        assertEquals(401, noAuth.statusCode())
    }

    // --- change-password -------------------------------------------------

    @Test
    fun `change-password rotates tokens and invalidates the old access token`() {
        val email = "cp-${UUID.randomUUID()}@example.com"
        val reg = registerFounder(email = email, password = "old-password-1")
        val oldToken = reg["accessToken"] as String

        val changed = post(
            "/api/v1/auth/change-password",
            mapOf("currentPassword" to "old-password-1", "newPassword" to "new-password-2"),
            mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to "device-1", "Authorization" to "Bearer $oldToken"),
        )
        assertEquals(200, changed.statusCode(), changed.body())
        val newToken = parse(changed.body())["accessToken"] as String

        // old access token is now invalid (token_version bumped)
        val withOld = get("/api/v1/me", mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $oldToken"))
        assertEquals(401, withOld.statusCode())

        // new token works
        val withNew = get("/api/v1/me", mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $newToken"))
        assertEquals(200, withNew.statusCode())

        // login reflects the new password
        assertEquals(200, post("/api/v1/auth/login", mapOf("login" to email, "password" to "new-password-2"), appHeaders).statusCode())
        assertEquals(401, post("/api/v1/auth/login", mapOf("login" to email, "password" to "old-password-1"), appHeaders).statusCode())
    }

    @Test
    fun `change-password with wrong current password is 401`() {
        val token = registerFounder(password = "the-current-1")["accessToken"] as String
        val res = post(
            "/api/v1/auth/change-password",
            mapOf("currentPassword" to "not-the-current", "newPassword" to "brand-new-pw-9"),
            mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to "device-1", "Authorization" to "Bearer $token"),
        )
        assertEquals(401, res.statusCode(), res.body())
        assertEquals("INVALID_CREDENTIALS", parse(res.body())["code"])
    }
}
