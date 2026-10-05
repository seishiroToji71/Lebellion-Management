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

/**
 * Enforcement of `must_change_password` on the whole authenticated surface: while the flag is set
 * (e.g. after a Founder resets a manager's password, giving a one-time temporary password), the user may
 * reach ONLY `POST /api/v1/auth/change-password`. Every other endpoint returns 403 `MUST_CHANGE_PASSWORD`,
 * a refreshed access token cannot bypass it (the flag is re-read from the DB per request), and a successful
 * change lifts the restriction immediately. Self-service password-reset (r4) is out of scope here.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
        "lebellion.auth.rate-limit.login-ip.limit=100000",
        "lebellion.auth.rate-limit.join-ip.limit=100000",
        "lebellion.auth.rate-limit.invite-create.limit=100000",
        "lebellion.auth.rate-limit.recovery-invite.limit=100000",
    ],
)
@Testcontainers
class MustChangePasswordIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper

    private val http: HttpClient = HttpClient.newHttpClient()

    // --- HTTP helpers ----------------------------------------------------

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

    private fun get(path: String, headers: Map<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path")).GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun user(body: String): Map<String, Any?> = parse(body)["user"] as Map<String, Any?>

    private fun bearer(token: String) = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")
    private fun deviceHeaders(deviceId: String) = mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to deviceId)

    // --- flow helpers ----------------------------------------------------

    private data class Founder(val token: String, val orgId: String)

    private fun registerFounder(): Founder {
        val res = post(
            "/api/v1/auth/register",
            mapOf(
                "organizationName" to "Org-${UUID.randomUUID()}",
                "fullName" to "The Founder",
                "email" to "founder-${UUID.randomUUID()}@example.com",
                "password" to "sup3rsecret!",
            ),
            deviceHeaders("founder-device"),
        )
        assertEquals(201, res.statusCode(), res.body())
        return Founder(parse(res.body())["accessToken"] as String, user(res.body())["organizationId"] as String)
    }

    private fun branchId(token: String): String {
        val res = post("/api/v1/branches", mapOf("name" to "Branch-${UUID.randomUUID()}"), bearer(token))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["id"] as String
    }

    private fun managerInviteCode(founderToken: String, branchId: String, email: String): String {
        val res = post("/api/v1/invites", mapOf("role" to "BRANCH_MANAGER", "branchId" to branchId, "email" to email), bearer(founderToken))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["code"] as String
    }

    private fun join(code: String, deviceId: String, password: String?): HttpResponse<String> {
        val body = buildMap<String, Any?> {
            put("inviteCode", code)
            put("fullName", "Branch Manager")
            if (password != null) put("password", password)
        }
        return post("/api/v1/auth/join", body, deviceHeaders(deviceId))
    }

    private fun login(loginId: String, password: String, deviceId: String): HttpResponse<String> =
        post("/api/v1/auth/login", mapOf("login" to loginId, "password" to password), deviceHeaders(deviceId))

    private fun refresh(refreshToken: String, deviceId: String): HttpResponse<String> =
        post("/api/v1/auth/refresh", mapOf("refreshToken" to refreshToken), deviceHeaders(deviceId))

    private fun recoveryInvite(token: String, userId: String): HttpResponse<String> =
        post("/api/v1/employees/$userId/recovery-invite", null, bearer(token))

    private fun changePassword(token: String, current: String, new: String, deviceId: String): HttpResponse<String> =
        post(
            "/api/v1/auth/change-password",
            mapOf("currentPassword" to current, "newPassword" to new),
            mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to deviceId, "Authorization" to "Bearer $token"),
        )

    /** Produces a logged-in BRANCH_MANAGER carrying must_change_password + the one-time temporary password. */
    private fun managerWithTemporaryPassword(founder: Founder): Pair<String, String> {
        val email = "mgr-${UUID.randomUUID()}@example.com"
        val joined = join(managerInviteCode(founder.token, branchId(founder.token), email), deviceId = "mgr-dev", password = "manager-pass-1")
        assertEquals(201, joined.statusCode(), joined.body())
        val mgrId = user(joined.body())["id"] as String

        val recovery = recoveryInvite(founder.token, mgrId)
        assertEquals(201, recovery.statusCode(), recovery.body())
        return email to (parse(recovery.body())["temporaryPassword"] as String)
    }

    // --- tests -----------------------------------------------------------

    @Test
    fun `temporary password reaches only change-password, data endpoints and refreshed tokens stay blocked`() {
        val founder = registerFounder()
        val (email, tempPassword) = managerWithTemporaryPassword(founder)

        // log in with the temporary password from a fresh device
        val loggedIn = login(email, tempPassword, "mgr-dev-2")
        assertEquals(200, loggedIn.statusCode(), loggedIn.body())
        assertEquals(true, user(loggedIn.body())["mustChangePassword"], "the profile tells the client to force a change")
        val access = parse(loggedIn.body())["accessToken"] as String
        val refreshTok = parse(loggedIn.body())["refreshToken"] as String

        // data endpoints are blocked with the dedicated code — the restriction is global, not just /me
        val me = get("/api/v1/me", bearer(access))
        assertEquals(403, me.statusCode(), me.body())
        assertEquals("MUST_CHANGE_PASSWORD", parse(me.body())["code"])
        val employees = get("/api/v1/employees", bearer(access))
        assertEquals(403, employees.statusCode(), employees.body())
        assertEquals("MUST_CHANGE_PASSWORD", parse(employees.body())["code"])

        // refresh itself works (token-free), but the NEW access token is still restricted — no bypass
        val refreshed = refresh(refreshTok, "mgr-dev-2")
        assertEquals(200, refreshed.statusCode(), refreshed.body())
        val refreshedAccess = parse(refreshed.body())["accessToken"] as String
        val meAfterRefresh = get("/api/v1/me", bearer(refreshedAccess))
        assertEquals(403, meAfterRefresh.statusCode(), "a refreshed token must not bypass the restriction")
        assertEquals("MUST_CHANGE_PASSWORD", parse(meAfterRefresh.body())["code"])

        // change-password IS reachable with the restricted token; it clears the flag and returns a fresh pair
        val changed = changePassword(access, current = tempPassword, new = "brand-new-pass-1", deviceId = "mgr-dev-2")
        assertEquals(200, changed.statusCode(), changed.body())
        assertEquals(false, user(changed.body())["mustChangePassword"])
        val newAccess = parse(changed.body())["accessToken"] as String

        // the restriction is lifted immediately: the fresh token reaches data endpoints
        assertEquals(200, get("/api/v1/me", bearer(newAccess)).statusCode(), "access is restored after the change")
    }

    @Test
    fun `a user without the flag is not restricted`() {
        val founder = registerFounder()
        assertEquals(200, get("/api/v1/me", bearer(founder.token)).statusCode(), "the gate must not over-block normal users")
    }
}
