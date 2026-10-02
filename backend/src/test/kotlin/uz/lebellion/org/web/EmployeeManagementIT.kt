package uz.lebellion.org.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.support.TestJwt
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Employees (block r, slice r3): scoped listing, immediate deactivation, and recovery invites.
 * Recovery is asymmetric by target role — EMPLOYEE (device change) revokes only at join, BRANCH_MANAGER
 * (Founder password reset) revokes + bumps tokenVersion at creation. Both directions are tested, so a
 * copy-paste of the manager's revoke into the shared path would fail the explicit EMPLOYEE guard.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
        "lebellion.auth.rate-limit.join-ip.limit=100000",
        "lebellion.auth.rate-limit.login-ip.limit=100000",
        "lebellion.auth.rate-limit.invite-create.limit=100000",
        "lebellion.auth.rate-limit.recovery-invite.limit=100000",
    ],
)
@Testcontainers
class EmployeeManagementIT {

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
    @Autowired lateinit var jdbc: JdbcTemplate

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

    private fun bearer(token: String) = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")
    private fun deviceHeaders(deviceId: String) = mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to deviceId)

    // --- principals / seeding --------------------------------------------

    private data class Founder(val token: String, val orgId: String, val founderId: String)

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
        @Suppress("UNCHECKED_CAST")
        val user = parse(res.body())["user"] as Map<String, Any?>
        return Founder(parse(res.body())["accessToken"] as String, user["organizationId"] as String, user["id"] as String)
    }

    private fun seedManager(orgId: String, branchId: String): AppUser = users.save(
        AppUser(
            organizationId = UUID.fromString(orgId),
            name = "Branch Manager",
            role = Role.BRANCH_MANAGER,
            email = "mgr-${UUID.randomUUID()}@example.com",
            branchId = UUID.fromString(branchId),
            passwordHash = "seeded-not-used",
        ),
    )

    private fun seedEmployee(orgId: String, unitId: String, active: Boolean = true): AppUser = users.save(
        AppUser(
            organizationId = UUID.fromString(orgId),
            name = "The Employee",
            role = Role.EMPLOYEE,
            unitId = UUID.fromString(unitId),
            isActive = active,
        ),
    )

    private fun tokenFor(user: AppUser): String =
        TestJwt.mint(appEncoder, subject = user.id!!.toString(), orgId = user.organizationId.toString(), role = user.role.name, tokenVersion = user.tokenVersion)

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

    private fun employeeInviteCode(founderToken: String, unitId: String): String {
        val res = post("/api/v1/invites", mapOf("role" to "EMPLOYEE", "unitId" to unitId), bearer(founderToken))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["code"] as String
    }

    private fun managerInviteCode(founderToken: String, branchId: String, email: String): String {
        val res = post("/api/v1/invites", mapOf("role" to "BRANCH_MANAGER", "branchId" to branchId, "email" to email), bearer(founderToken))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["code"] as String
    }

    private fun join(code: String, deviceId: String, password: String? = null): HttpResponse<String> {
        val body = buildMap<String, Any?> {
            put("inviteCode", code)
            put("fullName", "Newcomer")
            if (password != null) put("password", password)
        }
        return post("/api/v1/auth/join", body, deviceHeaders(deviceId))
    }

    private fun refresh(refreshToken: String, deviceId: String): HttpResponse<String> =
        post("/api/v1/auth/refresh", mapOf("refreshToken" to refreshToken), deviceHeaders(deviceId))

    private fun login(loginId: String, password: String, deviceId: String): HttpResponse<String> =
        post("/api/v1/auth/login", mapOf("login" to loginId, "password" to password), deviceHeaders(deviceId))

    private fun recoveryInvite(token: String, employeeId: String): HttpResponse<String> =
        post("/api/v1/employees/$employeeId/recovery-invite", null, bearer(token))

    private fun deactivate(token: String, employeeId: String): HttpResponse<String> =
        post("/api/v1/employees/$employeeId/deactivate", null, bearer(token))

    private fun auditCount(eventType: String, targetId: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE event_type = ? AND target_id = CAST(? AS uuid)",
            Long::class.java, eventType, targetId,
        )!!

    private fun activeRefreshCount(userId: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM refresh_token WHERE user_id = CAST(? AS uuid) AND revoked_at IS NULL",
            Long::class.java, userId,
        )!!

    private fun pendingRecoveryCount(targetId: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM invite WHERE target_employee_id = CAST(? AS uuid) AND status = 'PENDING'",
            Long::class.java, targetId,
        )!!

    // --- list -------------------------------------------------------------

    @Test
    fun `founder lists all staff, a manager only their own branch`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")
        val aUnit = unitId(founder.token, a)
        val bUnit = unitId(founder.token, b)
        val mgrA = seedManager(founder.orgId, a)
        val empA = seedEmployee(founder.orgId, aUnit)
        val empB = seedEmployee(founder.orgId, bUnit)

        val founderSees = ids(get("/api/v1/employees", bearer(founder.token)).body()).toSet()
        assertEquals(setOf(founder.founderId, mgrA.id.toString(), empA.id.toString(), empB.id.toString()), founderSees)

        val mgrSees = ids(get("/api/v1/employees", bearer(tokenFor(mgrA))).body()).toSet()
        assertEquals(setOf(mgrA.id.toString(), empA.id.toString()), mgrSees, "a manager sees their own branch (themselves + its employees)")

        assertEquals(403, get("/api/v1/employees", bearer(tokenFor(empA))).statusCode())

        // tenant isolation
        val other = registerFounder()
        assertFalse(ids(get("/api/v1/employees", bearer(other.token)).body()).contains(empA.id.toString()))
    }

    @Test
    fun `employee list filters by branch, unit and active`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")
        val a1 = unitId(founder.token, a)
        val a2 = unitId(founder.token, a)
        val b1 = unitId(founder.token, b)
        val empA1 = seedEmployee(founder.orgId, a1)
        val empA2 = seedEmployee(founder.orgId, a2, active = false)
        val empB1 = seedEmployee(founder.orgId, b1)

        fun q(params: String) = ids(get("/api/v1/employees?$params", bearer(founder.token)).body()).toSet()
        assertEquals(setOf(empA1.id.toString(), empA2.id.toString()), q("branchId=$a"), "branch filter via resolved (unit) branch")
        assertEquals(setOf(empA1.id.toString()), q("unitId=$a1"))
        assertEquals(setOf(empB1.id.toString()), q("branchId=$b&active=true"))
        assertEquals(setOf(empA2.id.toString()), q("active=false"))
    }

    // --- deactivate -------------------------------------------------------

    @Test
    fun `deactivate cuts access and refresh immediately and is idempotent`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))
        val joined = join(employeeInviteCode(founder.token, unit), deviceId = "emp-dev")
        assertEquals(201, joined.statusCode(), joined.body())
        @Suppress("UNCHECKED_CAST")
        val empId = (parse(joined.body())["user"] as Map<String, Any?>)["id"] as String
        val access = parse(joined.body())["accessToken"] as String
        val refreshTok = parse(joined.body())["refreshToken"] as String

        // the employee's own token works before deactivation
        assertEquals(200, get("/api/v1/me", bearer(access)).statusCode())

        val res = deactivate(founder.token, empId)
        assertEquals(200, res.statusCode(), res.body())
        assertEquals(false, parse(res.body())["active"])

        assertEquals(401, get("/api/v1/me", bearer(access)).statusCode(), "access dies on the very next request")
        assertEquals(401, refresh(refreshTok, "emp-dev").statusCode(), "refresh family is revoked")
        assertEquals(1L, auditCount("EMPLOYEE_DEACTIVATED", empId))

        // idempotent
        assertEquals(200, deactivate(founder.token, empId).statusCode())
    }

    @Test
    fun `deactivate is confined by role and branch, and refuses self`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")
        val empA = seedEmployee(founder.orgId, unitId(founder.token, a))
        val empB = seedEmployee(founder.orgId, unitId(founder.token, b))
        val mgrA = seedManager(founder.orgId, a)
        val mgrAToken = tokenFor(mgrA)

        // founder cannot deactivate themselves
        assertEquals(409, deactivate(founder.token, founder.founderId).statusCode())
        // manager of A deactivates A's employee, but not B's (404) nor another manager (404)
        assertEquals(200, deactivate(mgrAToken, empA.id.toString()).statusCode())
        assertEquals(404, deactivate(mgrAToken, empB.id.toString()).statusCode())
        assertEquals(404, deactivate(mgrAToken, mgrA.id.toString()).statusCode(), "a manager cannot deactivate a manager")
        // employee cannot deactivate; unknown id is 404
        assertEquals(403, deactivate(tokenFor(empB), empA.id.toString()).statusCode())
        assertEquals(404, deactivate(founder.token, UUID.randomUUID().toString()).statusCode())
        // tenant isolation
        val other = registerFounder()
        assertEquals(404, deactivate(other.token, empB.id.toString()).statusCode())
    }

    // --- recovery invite: EMPLOYEE (asymmetry negative guard) -------------

    @Test
    fun `employee recovery does NOT touch sessions or tokenVersion at creation, only at join`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))
        val joined = join(employeeInviteCode(founder.token, unit), deviceId = "emp-dev")
        @Suppress("UNCHECKED_CAST")
        val empId = (parse(joined.body())["user"] as Map<String, Any?>)["id"] as String
        val oldRefresh = parse(joined.body())["refreshToken"] as String
        val tvBefore = users.findById(UUID.fromString(empId)).orElseThrow().tokenVersion

        val res = recoveryInvite(founder.token, empId)
        assertEquals(201, res.statusCode(), res.body())
        assertEquals("EMPLOYEE", parse(res.body())["role"])
        assertNull(parse(res.body())["temporaryPassword"], "an employee has no password — no temp password")

        // mirror of the manager test: creation must NOT revoke or bump for an EMPLOYEE
        val after = users.findById(UUID.fromString(empId)).orElseThrow()
        assertEquals(tvBefore, after.tokenVersion, "tokenVersion must NOT be bumped at creation for an employee")
        assertTrue(after.isActive)
        assertTrue(activeRefreshCount(empId) >= 1, "the old session must stay alive until join")
        assertEquals(1L, auditCount("RECOVERY_INVITE_CREATED", empId))

        // the revoke happens at join (re-bind), as designed
        assertEquals(201, join(parse(res.body())["code"] as String, deviceId = "emp-dev-2").statusCode())
        assertEquals(401, refresh(oldRefresh, "emp-dev").statusCode(), "the old session is revoked at join")
    }

    // --- recovery invite: BRANCH_MANAGER (founder password reset) ---------

    @Test
    fun `manager recovery resets the password and kills the session immediately`() {
        val founder = registerFounder()
        val branch = branchId(founder.token)
        val email = "mgr-${UUID.randomUUID()}@example.com"
        val joined = join(managerInviteCode(founder.token, branch, email), deviceId = "mgr-dev", password = "manager-pass-1")
        assertEquals(201, joined.statusCode(), joined.body())
        @Suppress("UNCHECKED_CAST")
        val mgrId = (parse(joined.body())["user"] as Map<String, Any?>)["id"] as String
        val oldAccess = parse(joined.body())["accessToken"] as String
        val oldRefresh = parse(joined.body())["refreshToken"] as String
        assertEquals(200, login(email, "manager-pass-1", "mgr-dev-2").statusCode(), "password works before the reset")

        val res = recoveryInvite(founder.token, mgrId)
        assertEquals(201, res.statusCode(), res.body())
        assertEquals("BRANCH_MANAGER", parse(res.body())["role"])
        val tempPassword = parse(res.body())["temporaryPassword"] as String
        assertTrue(tempPassword.isNotBlank(), "a manager reset returns a one-time temporary password")

        // immediate lockout at creation (compromise scenario)
        assertEquals(401, get("/api/v1/me", bearer(oldAccess)).statusCode(), "tokenVersion bumped -> access dies now")
        assertEquals(401, refresh(oldRefresh, "mgr-dev").statusCode(), "sessions revoked now, not at join")
        assertEquals(401, login(email, "manager-pass-1", "mgr-dev-3").statusCode(), "the old password no longer works")
        assertEquals(200, login(email, tempPassword, "mgr-dev-4").statusCode(), "the temporary password works")
        assertTrue(users.findById(UUID.fromString(mgrId)).orElseThrow().mustChangePassword)
        assertEquals(1L, auditCount("PASSWORD_RESET_BY_FOUNDER", mgrId))

        // the recovery code still re-binds a device passwordlessly
        assertEquals(201, join(parse(res.body())["code"] as String, deviceId = "mgr-dev-5").statusCode())
    }

    // --- recovery invite: scoping & guards --------------------------------

    @Test
    fun `recovery-invite is confined by role and branch`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")
        val empA = seedEmployee(founder.orgId, unitId(founder.token, a))
        val empB = seedEmployee(founder.orgId, unitId(founder.token, b))
        val mgrA = seedManager(founder.orgId, a)
        val mgrAToken = tokenFor(mgrA)

        // manager of A recovers A's employee, but not B's (404) nor a manager (404 — founder-only)
        assertEquals(201, recoveryInvite(mgrAToken, empA.id.toString()).statusCode())
        assertEquals(404, recoveryInvite(mgrAToken, empB.id.toString()).statusCode())
        assertEquals(404, recoveryInvite(mgrAToken, mgrA.id.toString()).statusCode())
        // a founder cannot be recovered via this endpoint
        assertEquals(409, recoveryInvite(founder.token, founder.founderId).statusCode())
        // employee cannot issue; unknown id is 404
        assertEquals(403, recoveryInvite(tokenFor(empA), empB.id.toString()).statusCode())
        assertEquals(404, recoveryInvite(founder.token, UUID.randomUUID().toString()).statusCode())
        // tenant isolation
        assertEquals(404, recoveryInvite(registerFounder().token, empA.id.toString()).statusCode())
    }

    @Test
    fun `only one pending recovery invite per user`() {
        val founder = registerFounder()
        val emp = seedEmployee(founder.orgId, unitId(founder.token, branchId(founder.token)))
        assertEquals(201, recoveryInvite(founder.token, emp.id.toString()).statusCode())
        // a second one while the first is still PENDING is rejected (uq_invite_pending_recovery)
        val second = recoveryInvite(founder.token, emp.id.toString())
        assertEquals(409, second.statusCode(), second.body())
        assertEquals("CONFLICT", parse(second.body())["code"])
    }

    @Test
    fun `two concurrent manager recovery-invites leave no partial side effects`() {
        val founder = registerFounder()
        val email = "mgr-${UUID.randomUUID()}@example.com"
        val joined = join(managerInviteCode(founder.token, branchId(founder.token), email), deviceId = "mgr-dev", password = "manager-pass-1")
        assertEquals(201, joined.statusCode(), joined.body())
        @Suppress("UNCHECKED_CAST")
        val mgrId = (parse(joined.body())["user"] as Map<String, Any?>)["id"] as String
        val oldRefresh = parse(joined.body())["refreshToken"] as String
        val tvBefore = users.findById(UUID.fromString(mgrId)).orElseThrow().tokenVersion

        // Fire both before awaiting either, so they contend on the same user row lock + pending-recovery index.
        fun fire() = http.sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/api/v1/employees/$mgrId/recovery-invite"))
                .header("Content-Type", "application/json")
                .header("X-App-Version", "1.4.0")
                .header("Authorization", "Bearer ${founder.token}")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val f1 = fire()
        val f2 = fire()
        val r1 = f1.get(20, TimeUnit.SECONDS)
        val r2 = f2.get(20, TimeUnit.SECONDS)
        val byStatus = listOf(r1, r2).associateBy { it.statusCode() }

        // exactly one winner, one loser, never a 5xx (the DB partial-unique + row lock serialize the race)
        assertEquals(setOf(201, 409), byStatus.keys, "expected one 201 and one 409, got ${r1.statusCode()}/${r2.statusCode()}: ${r1.body()} | ${r2.body()}")

        // the loser left NO partial side effects — the whole losing transaction rolled back:
        val after = users.findById(UUID.fromString(mgrId)).orElseThrow()
        assertEquals(tvBefore + 1, after.tokenVersion, "tokenVersion must be bumped exactly once (loser's bump rolled back)")
        assertTrue(after.mustChangePassword)
        assertEquals(1L, pendingRecoveryCount(mgrId), "exactly one pending recovery invite survives")
        assertEquals(1L, auditCount("PASSWORD_RESET_BY_FOUNDER", mgrId), "only the winning reset is audited")

        // the single effective password is the WINNER's temp password; the old one is dead; revoke happened once
        val winnerTemp = parse(byStatus.getValue(201).body())["temporaryPassword"] as String
        assertEquals(401, login(email, "manager-pass-1", "dev-old").statusCode(), "the original password is gone")
        assertEquals(200, login(email, winnerTemp, "dev-win").statusCode(), "the winner's temp password is the one in effect")
        assertEquals(401, refresh(oldRefresh, "mgr-dev").statusCode(), "the manager's prior session was revoked")
    }
}
