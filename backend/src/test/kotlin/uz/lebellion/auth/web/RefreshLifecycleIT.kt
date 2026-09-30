package uz.lebellion.auth.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import uz.lebellion.auth.domain.Invite
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.InviteRepository
import uz.lebellion.auth.support.MutableClock
import uz.lebellion.auth.support.MutableClockConfig
import uz.lebellion.auth.token.HmacCodec
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Time-dependent refresh behaviour, driven by a [MutableClock] so nothing sleeps:
 *  - the 90-day absolute cap is enforced for managers/founders and rotation never extends it;
 *  - EMPLOYEE sessions have no absolute cap;
 *  - a deactivated user is locked out on the next refresh AND the family is revoked (variant B).
 *
 * Sliding windows are set very large (P365D) so the absolute cap is the only thing that can expire a
 * session within the tests.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
        "lebellion.auth.rate-limit.join-ip.limit=100000",
        "lebellion.auth.refresh.manager-sliding=P365D",
        "lebellion.auth.refresh.manager-absolute=P90D",
        "lebellion.auth.refresh.employee-sliding=P365D",
    ],
)
@Import(MutableClockConfig::class)
@Testcontainers
class RefreshLifecycleIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var invites: InviteRepository

    @Autowired
    @Qualifier("inviteHmacCodec")
    lateinit var inviteHmac: HmacCodec

    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun resetClock() {
        clock.setTo(Instant.now())
    }

    // --- helpers ---------------------------------------------------------

    private fun post(path: String, body: Map<String, Any?>, device: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .header("X-App-Version", "1.4.0")
            .header("X-Device-Id", device)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    private fun refresh(token: String, device: String = "device-1") =
        post("/api/v1/auth/refresh", mapOf("refreshToken" to token), device)

    private fun registerFounder(device: String = "device-1"): Map<String, Any?> {
        val res = post(
            "/api/v1/auth/register",
            mapOf(
                "organizationName" to "Org-${UUID.randomUUID()}",
                "fullName" to "The Founder",
                "email" to "founder-${UUID.randomUUID()}@example.com",
                "password" to "sup3rsecret!",
            ),
            device,
        )
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())
    }

    // --- absolute cap ----------------------------------------------------

    @Test
    fun `manager session is refused past the 90-day cap and rotation does not extend it`() {
        val rt0 = registerFounder()["refreshToken"] as String // absolute cap anchored at "now" + 90d

        // Two rotations, each well inside the sliding window; the cap must not move.
        clock.advance(Duration.ofDays(30))
        val r1 = refresh(rt0)
        assertEquals(200, r1.statusCode(), r1.body())
        val rt1 = parse(r1.body())["refreshToken"] as String
        clock.advance(Duration.ofDays(30)) // total 60d
        val r2 = refresh(rt1)
        assertEquals(200, r2.statusCode(), r2.body())
        val rt2 = parse(r2.body())["refreshToken"] as String

        // Cross the 90d absolute cap (total 91d) — sliding (365d) is nowhere near, so only the cap can bite.
        clock.advance(Duration.ofDays(31))
        val expired = refresh(rt2)
        assertEquals(401, expired.statusCode(), expired.body())
        assertEquals("INVALID_TOKEN", parse(expired.body())["code"])
    }

    @Test
    fun `employee session has no absolute cap and refreshes well past 90 days`() {
        val ctx = bootstrap()
        val code = "EMP-${UUID.randomUUID()}"
        seedEmployeeInvite(ctx, code)
        val join = post("/api/v1/auth/join", mapOf("inviteCode" to code, "fullName" to "Worker"), "emp-device")
        assertEquals(201, join.statusCode(), join.body())
        val rt0 = parse(join.body())["refreshToken"] as String

        clock.advance(Duration.ofDays(120)) // past any manager cap, still inside the 365d sliding window
        val res = refresh(rt0, device = "emp-device")
        assertEquals(200, res.statusCode(), res.body())
    }

    // --- deactivated user = 401 + family revoke (variant B) --------------

    @Test
    fun `refresh of a deactivated user is 401 and revokes the whole family`() {
        val reg = registerFounder()
        val userId = UUID.fromString((reg["user"] as Map<*, *>)["id"] as String)
        val rt0 = reg["refreshToken"] as String
        val rt1 = parse(refresh(rt0).body())["refreshToken"] as String // rt0 rotated, rt1 live

        jdbc.update("UPDATE app_user SET is_active = false WHERE id = CAST(? AS uuid)", userId.toString())

        val res = refresh(rt1)
        assertEquals(401, res.statusCode(), res.body())
        assertEquals("INVALID_TOKEN", parse(res.body())["code"])

        // the whole family (live rt1 + rotated rt0) is revoked with the distinct USER_INACTIVE reason
        val inactive = jdbc.queryForObject(
            "SELECT count(*) FROM refresh_token WHERE user_id = CAST(? AS uuid) AND revoked_reason = 'USER_INACTIVE'",
            Long::class.java,
            userId.toString(),
        )
        assertTrue((inactive ?: 0) >= 2, "expected the family revoked as USER_INACTIVE, got $inactive")
        // and it stays 401 afterwards
        assertEquals(401, refresh(rt1).statusCode())
    }

    // --- seeding (mirrors JoinIT) ----------------------------------------

    private data class Ctx(val orgId: UUID, val founderId: UUID, val unitId: UUID)

    private fun bootstrap(): Ctx {
        val u = registerFounder(device = "founder-device")["user"] as Map<*, *>
        val orgId = UUID.fromString(u["organizationId"] as String)
        val founderId = UUID.fromString(u["id"] as String)
        val branchId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO branch (id, organization_id, name) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)",
            branchId.toString(), orgId.toString(), "Branch",
        )
        val unitId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO unit (id, organization_id, branch_id, name) VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?)",
            unitId.toString(), orgId.toString(), branchId.toString(), "Kitchen",
        )
        return Ctx(orgId, founderId, unitId)
    }

    private fun seedEmployeeInvite(ctx: Ctx, rawCode: String) {
        invites.save(
            Invite(
                organizationId = ctx.orgId,
                codeHmac = inviteHmac.hmacHex(rawCode),
                role = Role.EMPLOYEE,
                expiresAt = Instant.now().plusSeconds(3600),
                createdBy = ctx.founderId,
                unitId = ctx.unitId,
            ),
        )
    }
}
