package uz.lebellion.auth.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import uz.lebellion.auth.domain.Invite
import uz.lebellion.auth.domain.InviteStatus
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.repo.InviteRepository
import uz.lebellion.auth.repo.RefreshTokenRepository
import uz.lebellion.auth.token.HmacCodec
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        // All tests share one localhost IP; raise the per-IP limits so the limiter doesn't
        // starve unrelated test cases (the limiter itself is covered by RateLimiterTest).
        "lebellion.auth.rate-limit.join-ip.limit=100000",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
    ],
)
@Testcontainers
class JoinIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var invites: InviteRepository
    @Autowired lateinit var users: AppUserRepository
    @Autowired lateinit var refreshTokens: RefreshTokenRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired
    @Qualifier("inviteHmacCodec")
    lateinit var inviteHmac: HmacCodec

    private val http: HttpClient = HttpClient.newHttpClient()

    // --- HTTP helpers ----------------------------------------------------

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

    @Suppress("UNCHECKED_CAST")
    private fun userOf(body: Map<String, Any?>): Map<String, Any?> = body["user"] as Map<String, Any?>

    // --- seeding ---------------------------------------------------------

    private data class Ctx(val orgId: UUID, val founderId: UUID, val branchId: UUID, val unitId: UUID)

    private fun bootstrap(): Ctx {
        val res = post(
            "/api/v1/auth/register",
            mapOf("organizationName" to "Org-${UUID.randomUUID()}", "fullName" to "Founder", "email" to "f-${UUID.randomUUID()}@x.com", "password" to "founder-pass-1"),
            mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to "founder-device"),
        )
        assertEquals(201, res.statusCode(), res.body())
        val u = userOf(parse(res.body()))
        val orgId = UUID.fromString(u["organizationId"] as String)
        val founderId = UUID.fromString(u["id"] as String)
        val branchId = UUID.randomUUID()
        jdbc.update("INSERT INTO branch (id, organization_id, name) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)", branchId.toString(), orgId.toString(), "Branch")
        val unitId = UUID.randomUUID()
        jdbc.update("INSERT INTO unit (id, organization_id, branch_id, name) VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?)", unitId.toString(), orgId.toString(), branchId.toString(), "Kitchen")
        return Ctx(orgId, founderId, branchId, unitId)
    }

    private fun seedInvite(
        ctx: Ctx,
        role: Role,
        rawCode: String,
        unitId: UUID? = null,
        branchId: UUID? = null,
        targetEmployeeId: UUID? = null,
        email: String? = null,
        phone: String? = null,
        expiresAt: Instant = Instant.now().plusSeconds(3600),
    ): Invite = invites.save(
        Invite(
            organizationId = ctx.orgId,
            codeHmac = inviteHmac.hmacHex(rawCode),
            role = role,
            expiresAt = expiresAt,
            createdBy = ctx.founderId,
            unitId = unitId,
            branchId = branchId,
            targetEmployeeId = targetEmployeeId,
            email = email,
            phone = phone,
        ),
    )

    private fun join(code: String, fullName: String = "Newcomer", password: String? = null, deviceId: String = "device-1"): HttpResponse<String> {
        val body = buildMap<String, Any?> {
            put("inviteCode", code)
            put("fullName", fullName)
            if (password != null) put("password", password)
        }
        return post("/api/v1/auth/join", body, mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to deviceId))
    }

    private fun auditCount(eventType: String, targetId: UUID): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE event_type = ? AND target_id = CAST(? AS uuid)",
            Long::class.java,
            eventType,
            targetId.toString(),
        )!!

    // --- tests -----------------------------------------------------------

    @Test
    fun `employee join creates an EMPLOYEE bound to the unit and audits`() {
        val ctx = bootstrap()
        val res = join(seedCode(ctx, Role.EMPLOYEE, unitId = ctx.unitId))
        assertEquals(201, res.statusCode(), res.body())
        val u = userOf(parse(res.body()))
        assertEquals("EMPLOYEE", u["role"])
        assertEquals(ctx.unitId.toString(), u["unitId"])
        assertEquals(1L, auditCount("EMPLOYEE_JOINED", UUID.fromString(u["id"] as String)))
    }

    @Test
    fun `manager join creates a BRANCH_MANAGER that can then log in`() {
        val ctx = bootstrap()
        val email = "mgr-${UUID.randomUUID()}@x.com"
        val code = "MANAGERCODE1"
        seedInvite(ctx, Role.BRANCH_MANAGER, code, branchId = ctx.branchId, email = email)
        val res = join(code, password = "manager-pass-1")
        assertEquals(201, res.statusCode(), res.body())
        val u = userOf(parse(res.body()))
        assertEquals("BRANCH_MANAGER", u["role"])
        assertEquals(ctx.branchId.toString(), u["branchId"])
        assertEquals(1L, auditCount("MANAGER_JOINED", UUID.fromString(u["id"] as String)))

        val login = post("/api/v1/auth/login", mapOf("login" to email, "password" to "manager-pass-1"), mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to "device-1"))
        assertEquals(200, login.statusCode(), login.body())
    }

    @Test
    fun `manager join without a password is 400`() {
        val ctx = bootstrap()
        val code = "MANAGERCODE2"
        seedInvite(ctx, Role.BRANCH_MANAGER, code, branchId = ctx.branchId, email = "mgr-${UUID.randomUUID()}@x.com")
        assertEquals(400, join(code, password = null).statusCode())
    }

    @Test
    fun `employee join with a password is 400`() {
        val ctx = bootstrap()
        assertEquals(400, join(seedCode(ctx, Role.EMPLOYEE, unitId = ctx.unitId), password = "should-not-be-here").statusCode())
    }

    @Test
    fun `unknown and expired codes both return 401`() {
        val ctx = bootstrap()
        assertEquals(401, join("NO-SUCH-CODE").statusCode())

        val expired = "EXPIREDCODE1"
        seedInvite(ctx, Role.EMPLOYEE, expired, unitId = ctx.unitId, expiresAt = Instant.now().minusSeconds(60))
        assertEquals(401, join(expired).statusCode())
    }

    @Test
    fun `used code returns 409 to the same device and 401 to another`() {
        val ctx = bootstrap()
        val code = seedCode(ctx, Role.EMPLOYEE, unitId = ctx.unitId)
        assertEquals(201, join(code, deviceId = "device-1").statusCode())

        val same = join(code, deviceId = "device-1")
        assertEquals(409, same.statusCode(), same.body())
        assertEquals("INVITE_ALREADY_USED", parse(same.body())["code"])

        assertEquals(401, join(code, deviceId = "device-2").statusCode())
    }

    @Test
    fun `failed manager password validation leaves the invite PENDING`() {
        val ctx = bootstrap()
        val code = "ATOMICCODE1"
        val invite = seedInvite(ctx, Role.BRANCH_MANAGER, code, branchId = ctx.branchId, email = "mgr-${UUID.randomUUID()}@x.com")
        assertEquals(400, join(code, password = null).statusCode())
        assertEquals(InviteStatus.PENDING, invites.findById(invite.id!!).get().status)
    }

    @Test
    fun `recovery join re-binds the user, revokes old sessions and audits`() {
        val ctx = bootstrap()
        // first join on device-1 -> creates an employee with an active session
        val first = join(seedCode(ctx, Role.EMPLOYEE, unitId = ctx.unitId), deviceId = "device-1")
        assertEquals(201, first.statusCode())
        val empId = UUID.fromString(userOf(parse(first.body()))["id"] as String)

        // founder issues a recovery invite for that employee; employee joins on a NEW device
        val recoveryCode = "RECOVERYCODE1"
        seedInvite(ctx, Role.EMPLOYEE, recoveryCode, targetEmployeeId = empId)
        val recovered = join(recoveryCode, deviceId = "device-2")
        assertEquals(201, recovered.statusCode(), recovered.body())
        assertEquals(empId.toString(), userOf(parse(recovered.body()))["id"])

        val tokens = refreshTokens.findByUserId(empId)
        val active = tokens.filter { it.revokedAt == null }
        assertEquals(1, active.size, "only the new session should be active")
        assertEquals("device-2", active.single().deviceId)
        assertTrue(tokens.any { it.deviceId == "device-1" && it.revokedAt != null }, "old device session must be revoked")
        assertEquals(1L, auditCount("RECOVERY_JOIN", empId))
    }

    private fun seedCode(ctx: Ctx, role: Role, unitId: UUID? = null, branchId: UUID? = null): String {
        val code = "CODE-${UUID.randomUUID()}"
        seedInvite(ctx, role, code, unitId = unitId, branchId = branchId)
        return code
    }
}
