package uz.lebellion.org.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.repo.InviteRepository
import uz.lebellion.auth.support.TestJwt
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/**
 * Invite management (block r, slice r2): create / list / reissue / revoke with role + branch scoping,
 * one-time plaintext code, computed EXPIRED status, and tenant isolation. The plaintext codes are
 * exercised end-to-end through the real join flow to prove reissue/revoke actually kill a code.
 *
 * Rate-limit buckets are bumped out of the way here (they share the in-memory limiter across the class);
 * the cap itself is asserted in [InviteRateLimitIT].
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
        "lebellion.auth.rate-limit.join-ip.limit=100000",
        "lebellion.auth.rate-limit.login-ip.limit=100000",
        "lebellion.auth.rate-limit.invite-create.limit=100000",
        "lebellion.auth.rate-limit.invite-reissue.limit=100000",
    ],
)
@Testcontainers
class InviteManagementIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var appEncoder: JwtEncoder
    @Autowired lateinit var users: AppUserRepository
    @Autowired lateinit var invites: InviteRepository

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
    private fun items(body: String): List<Map<String, Any?>> = parse(body)["items"] as List<Map<String, Any?>>

    private fun ids(body: String): List<String> = items(body).map { it["id"] as String }

    // --- principals ------------------------------------------------------

    private data class Founder(val token: String, val orgId: String)

    private fun bearer(token: String) = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")

    private fun registerFounder(): Founder {
        val res = post(
            "/api/v1/auth/register",
            mapOf(
                "organizationName" to "Org-${UUID.randomUUID()}",
                "fullName" to "The Founder",
                "email" to "founder-${UUID.randomUUID()}@example.com",
                "password" to "sup3rsecret!",
            ),
            mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to "device-1"),
        )
        assertEquals(201, res.statusCode(), res.body())
        val body = parse(res.body())
        @Suppress("UNCHECKED_CAST")
        val user = body["user"] as Map<String, Any?>
        return Founder(body["accessToken"] as String, user["organizationId"] as String)
    }

    private fun managerToken(orgId: String, branchId: String): String {
        val manager = users.save(
            AppUser(
                organizationId = UUID.fromString(orgId),
                name = "Branch Manager",
                role = Role.BRANCH_MANAGER,
                email = "mgr-${UUID.randomUUID()}@example.com",
                branchId = UUID.fromString(branchId),
                passwordHash = "seeded-not-used",
            ),
        )
        return TestJwt.mint(appEncoder, subject = manager.id!!.toString(), orgId = orgId, role = "BRANCH_MANAGER", tokenVersion = 0)
    }

    private fun employeeToken(orgId: String, unitId: String): String {
        val employee = users.save(
            AppUser(
                organizationId = UUID.fromString(orgId),
                name = "The Employee",
                role = Role.EMPLOYEE,
                unitId = UUID.fromString(unitId),
            ),
        )
        return TestJwt.mint(appEncoder, subject = employee.id!!.toString(), orgId = orgId, role = "EMPLOYEE", tokenVersion = 0)
    }

    // --- domain helpers --------------------------------------------------

    private fun branchId(token: String, name: String = "Branch-${UUID.randomUUID()}"): String {
        val res = post("/api/v1/branches", mapOf("name" to name), bearer(token))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["id"] as String
    }

    private fun unitId(token: String, branchId: String): String {
        val res = post("/api/v1/branches/$branchId/units", mapOf("name" to "Unit-${UUID.randomUUID()}"), bearer(token))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["id"] as String
    }

    private fun createInvite(token: String, body: Map<String, Any?>): HttpResponse<String> =
        post("/api/v1/invites", body, bearer(token))

    private fun employeeInvite(token: String, unitId: String): Map<String, Any?> {
        val res = createInvite(token, mapOf("role" to "EMPLOYEE", "unitId" to unitId))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())
    }

    private fun join(code: String, password: String? = null, deviceId: String = "device-${UUID.randomUUID()}"): HttpResponse<String> {
        val body = buildMap<String, Any?> {
            put("inviteCode", code)
            put("fullName", "Newcomer")
            if (password != null) put("password", password)
        }
        return post("/api/v1/auth/join", body, mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to deviceId))
    }

    // --- create ----------------------------------------------------------

    @Test
    fun `founder creates an employee invite that derives its branch and can be joined once`() {
        val founder = registerFounder()
        val branch = branchId(founder.token)
        val unit = unitId(founder.token, branch)

        val invite = employeeInvite(founder.token, unit)
        assertNotNull(invite["id"])
        assertEquals("EMPLOYEE", invite["role"])
        assertEquals(branch, invite["branchId"], "branch is derived from the unit")
        assertEquals(unit, invite["unitId"])
        val code = invite["code"] as String
        assertTrue(code.isNotBlank(), "the plaintext code is returned once on create")

        val joined = join(code)
        assertEquals(201, joined.statusCode(), joined.body())
        @Suppress("UNCHECKED_CAST")
        assertEquals("EMPLOYEE", (parse(joined.body())["user"] as Map<String, Any?>)["role"])
    }

    @Test
    fun `a branch-manager invite needs a contact, then the manager can join`() {
        val founder = registerFounder()
        val branch = branchId(founder.token)

        val noContact = createInvite(founder.token, mapOf("role" to "BRANCH_MANAGER", "branchId" to branch))
        assertEquals(400, noContact.statusCode(), noContact.body())
        assertEquals("VALIDATION", parse(noContact.body())["code"])

        val res = createInvite(
            founder.token,
            mapOf("role" to "BRANCH_MANAGER", "branchId" to branch, "email" to "newmgr-${UUID.randomUUID()}@example.com"),
        )
        assertEquals(201, res.statusCode(), res.body())
        val invite = parse(res.body())
        assertEquals(branch, invite["branchId"])
        assertNull(invite["unitId"], "a manager invite carries no unit")

        val joined = join(invite["code"] as String, password = "manager-pass-1")
        assertEquals(201, joined.statusCode(), joined.body())
        @Suppress("UNCHECKED_CAST")
        assertEquals("BRANCH_MANAGER", (parse(joined.body())["user"] as Map<String, Any?>)["role"])
    }

    @Test
    fun `employee-invite shape is validated`() {
        val founder = registerFounder()
        val branch = branchId(founder.token)
        val unit = unitId(founder.token, branch)

        // branch/email/phone are not allowed for an EMPLOYEE invite
        assertEquals(400, createInvite(founder.token, mapOf("role" to "EMPLOYEE", "unitId" to unit, "branchId" to branch)).statusCode())
        assertEquals(400, createInvite(founder.token, mapOf("role" to "EMPLOYEE", "unitId" to unit, "email" to "x@y.com")).statusCode())
        // unitId is required
        assertEquals(400, createInvite(founder.token, mapOf("role" to "EMPLOYEE")).statusCode())
        // an unknown unit is a 404 (no existence leak)
        val ghost = createInvite(founder.token, mapOf("role" to "EMPLOYEE", "unitId" to UUID.randomUUID().toString()))
        assertEquals(404, ghost.statusCode(), ghost.body())
    }

    @Test
    fun `invite creation is confined by role and branch`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")
        val aUnit = unitId(founder.token, a)
        val bUnit = unitId(founder.token, b)

        val mgr = managerToken(founder.orgId, a)
        // manager may invite an EMPLOYEE into their own branch
        assertEquals(201, createInvite(mgr, mapOf("role" to "EMPLOYEE", "unitId" to aUnit)).statusCode())
        // but not into another branch (reported as 404, not 403 — no existence leak)
        val cross = createInvite(mgr, mapOf("role" to "EMPLOYEE", "unitId" to bUnit))
        assertEquals(404, cross.statusCode(), cross.body())
        assertEquals("NOT_FOUND", parse(cross.body())["code"])
        // and may not mint a BRANCH_MANAGER invite at all
        val escalate = createInvite(mgr, mapOf("role" to "BRANCH_MANAGER", "branchId" to a, "email" to "x@y.com"))
        assertEquals(403, escalate.statusCode(), escalate.body())
        assertEquals("FORBIDDEN", parse(escalate.body())["code"])

        // an employee cannot create invites, and an anonymous caller is 401
        val emp = employeeToken(founder.orgId, aUnit)
        assertEquals(403, createInvite(emp, mapOf("role" to "EMPLOYEE", "unitId" to aUnit)).statusCode())
        assertEquals(401, post("/api/v1/invites", mapOf("role" to "EMPLOYEE", "unitId" to aUnit), mapOf("X-App-Version" to "1.4.0")).statusCode())
    }

    // --- reissue ----------------------------------------------------------

    @Test
    fun `reissue kills the old code, mints a working one, and 409s a consumed invite`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))
        val invite = employeeInvite(founder.token, unit)
        val id = invite["id"] as String
        val oldCode = invite["code"] as String

        val reissued = post("/api/v1/invites/$id/reissue", null, bearer(founder.token))
        assertEquals(200, reissued.statusCode(), reissued.body())
        val newCode = parse(reissued.body())["code"] as String
        assertNotEquals(oldCode, newCode)
        assertEquals(id, parse(reissued.body())["id"], "reissue keeps the same invite id")

        assertEquals(401, join(oldCode).statusCode(), "the old code must no longer be redeemable")
        assertEquals(201, join(newCode).statusCode(), "the fresh code works")

        // now that it is USED, it is no longer reissuable
        val again = post("/api/v1/invites/$id/reissue", null, bearer(founder.token))
        assertEquals(409, again.statusCode(), again.body())
        assertEquals("CONFLICT", parse(again.body())["code"])
    }

    // --- revoke -----------------------------------------------------------

    @Test
    fun `revoke kills a pending invite, is idempotent, and 409s a used one`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))

        val pending = employeeInvite(founder.token, unit)
        val pendingId = pending["id"] as String
        assertEquals(204, post("/api/v1/invites/$pendingId/revoke", null, bearer(founder.token)).statusCode())
        assertEquals(401, join(pending["code"] as String).statusCode(), "a revoked code cannot be redeemed")
        // idempotent, and a revoked invite is not reissuable
        assertEquals(204, post("/api/v1/invites/$pendingId/revoke", null, bearer(founder.token)).statusCode())
        assertEquals(409, post("/api/v1/invites/$pendingId/reissue", null, bearer(founder.token)).statusCode())

        // a consumed invite cannot be revoked
        val used = employeeInvite(founder.token, unit)
        assertEquals(201, join(used["code"] as String).statusCode())
        val revokeUsed = post("/api/v1/invites/${used["id"]}/revoke", null, bearer(founder.token))
        assertEquals(409, revokeUsed.statusCode(), revokeUsed.body())
        assertEquals("CONFLICT", parse(revokeUsed.body())["code"])
    }

    // --- list -------------------------------------------------------------

    @Test
    fun `list never leaks code or contact and exposes derived branch plus metadata`() {
        val founder = registerFounder()
        val branch = branchId(founder.token)
        val unit = unitId(founder.token, branch)
        val emp = employeeInvite(founder.token, unit)["id"] as String
        createInvite(founder.token, mapOf("role" to "BRANCH_MANAGER", "branchId" to branch, "email" to "m@x.com", "phone" to "+998901112233"))

        val list = get("/api/v1/invites", bearer(founder.token))
        assertEquals(200, list.statusCode(), list.body())
        val rows = items(list.body())
        assertEquals(2, rows.size)
        rows.forEach {
            assertFalse(it.containsKey("code"), "the list must never carry the code")
            assertFalse(it.containsKey("email"), "the list must never carry personal data")
            assertFalse(it.containsKey("phone"), "the list must never carry personal data")
            assertNotNull(it["createdBy"])
            assertNotNull(it["createdAt"])
            assertNotNull(it["expiresAt"])
        }
        val empRow = rows.single { it["id"] == emp }
        assertEquals(branch, empRow["branchId"], "an employee invite exposes its derived branch")
        assertEquals("PENDING", empRow["status"])
    }

    @Test
    fun `list filters by status and computes EXPIRED from the deadline`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))
        val live = employeeInvite(founder.token, unit)["id"] as String
        val stale = employeeInvite(founder.token, unit)["id"] as String

        // push one invite's deadline into the past — stored PENDING, so it reads as EXPIRED
        val staleInvite = invites.findById(UUID.fromString(stale)).orElseThrow()
        staleInvite.expiresAt = Instant.now().minusSeconds(300)
        invites.save(staleInvite)

        assertEquals(listOf(live), ids(get("/api/v1/invites?status=PENDING", bearer(founder.token)).body()))
        assertEquals(listOf(stale), ids(get("/api/v1/invites?status=EXPIRED", bearer(founder.token)).body()))
        assertTrue(ids(get("/api/v1/invites?status=USED", bearer(founder.token)).body()).isEmpty())

        // an EXPIRED (stored PENDING) invite is still reissuable
        assertEquals(200, post("/api/v1/invites/$stale/reissue", null, bearer(founder.token)).statusCode())
    }

    @Test
    fun `a manager lists only their own branch's employee invites`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")
        val aUnit = unitId(founder.token, a)
        val bUnit = unitId(founder.token, b)
        val aEmp = employeeInvite(founder.token, aUnit)["id"] as String
        employeeInvite(founder.token, bUnit) // another branch
        createInvite(founder.token, mapOf("role" to "BRANCH_MANAGER", "branchId" to a, "email" to "m@x.com")) // manager invite in A

        val mgr = managerToken(founder.orgId, a)
        assertEquals(listOf(aEmp), ids(get("/api/v1/invites", bearer(mgr)).body()), "only A's employee invite is visible")
    }

    @Test
    fun `invites paginate through a stable cursor with no gaps or duplicates`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))
        val created = (1..5).map { employeeInvite(founder.token, unit)["id"] as String }.toSet()

        val collected = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val q = "/api/v1/invites?limit=2" + (cursor?.let { "&cursor=$it" } ?: "")
            val res = get(q, bearer(founder.token))
            assertEquals(200, res.statusCode(), res.body())
            assertTrue(items(res.body()).size <= 2)
            collected += ids(res.body())
            cursor = parse(res.body())["nextCursor"] as String?
            pages++
        } while (cursor != null && pages < 10)

        assertNull(cursor, "pagination must terminate")
        assertEquals(created, collected.toSet())
        assertEquals(collected.size, collected.toSet().size, "no id may appear twice across pages")
    }

    // --- scope + tenant isolation ----------------------------------------

    @Test
    fun `reissue and revoke respect branch scope and tenant isolation`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")
        val bInvite = employeeInvite(founder.token, unitId(founder.token, b))["id"] as String

        // a manager of A cannot touch an invite in branch B (404, no leak)
        val mgrA = managerToken(founder.orgId, a)
        assertEquals(404, post("/api/v1/invites/$bInvite/reissue", null, bearer(mgrA)).statusCode())
        assertEquals(404, post("/api/v1/invites/$bInvite/revoke", null, bearer(mgrA)).statusCode())

        // another org's founder sees none of it and cannot act on it
        val other = registerFounder()
        assertFalse(ids(get("/api/v1/invites", bearer(other.token)).body()).contains(bInvite))
        assertEquals(404, post("/api/v1/invites/$bInvite/revoke", null, bearer(other.token)).statusCode())
        // an unknown id is a 404 too
        assertEquals(404, post("/api/v1/invites/${UUID.randomUUID()}/revoke", null, bearer(founder.token)).statusCode())
    }
}
