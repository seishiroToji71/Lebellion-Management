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

/**
 * Branches & units (block г, slice r1): role scoping (FOUNDER vs BRANCH_MANAGER vs EMPLOYEE),
 * per-branch confinement of managers, tenant isolation, and keyset (limit+cursor) pagination.
 *
 * Managers/employees are seeded directly into `app_user` (the invite+join flow lands in a later slice);
 * a valid access token is then minted for them via [TestJwt] — the DB-backed converter still enforces
 * is_active / org_id / role, so these are genuine principals.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
    ],
)
@Testcontainers
class OrgManagementIT {

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

    /** Seeds a BRANCH_MANAGER bound to [branchId] and mints a valid access token for it. */
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

    /** Seeds an EMPLOYEE bound to [unitId] and mints a valid access token for it. */
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

    private fun createBranch(token: String, name: String = "Branch-${UUID.randomUUID()}"): Map<String, Any?> {
        val res = post("/api/v1/branches", mapOf("name" to name), bearer(token))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())
    }

    private fun branchId(token: String, name: String = "Branch-${UUID.randomUUID()}"): String =
        createBranch(token, name)["id"] as String

    private fun createUnit(token: String, branchId: String, name: String = "Unit-${UUID.randomUUID()}"): HttpResponse<String> =
        post("/api/v1/branches/$branchId/units", mapOf("name" to name), bearer(token))

    private fun unitId(token: String, branchId: String): String {
        val res = createUnit(token, branchId)
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["id"] as String
    }

    // --- branches: create --------------------------------------------------

    @Test
    fun `founder creates a branch, but manager and employee cannot`() {
        val founder = registerFounder()

        val created = createBranch(founder.token, name = "HQ")
        assertNotNull(created["id"])
        assertEquals(founder.orgId, created["organizationId"])
        assertEquals("HQ", created["name"])
        assertTrue(created["createdAt"] is String, "createdAt must serialize as an ISO-8601 string")

        val branch = created["id"] as String
        val unit = unitId(founder.token, branch)

        // a manager of that branch may NOT create branches
        val mgr = managerToken(founder.orgId, branch)
        val mgrRes = post("/api/v1/branches", mapOf("name" to "Nope"), bearer(mgr))
        assertEquals(403, mgrRes.statusCode(), mgrRes.body())
        assertEquals("FORBIDDEN", parse(mgrRes.body())["code"])

        // nor an employee
        val emp = employeeToken(founder.orgId, unit)
        assertEquals(403, post("/api/v1/branches", mapOf("name" to "Nope"), bearer(emp)).statusCode())

        // nor an anonymous caller
        assertEquals(401, post("/api/v1/branches", mapOf("name" to "Nope"), mapOf("X-App-Version" to "1.4.0")).statusCode())
    }

    // --- branches: list scoping -------------------------------------------

    @Test
    fun `founder lists every branch, a manager sees only their own`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")
        val c = branchId(founder.token, "C")

        val founderList = get("/api/v1/branches", bearer(founder.token))
        assertEquals(200, founderList.statusCode(), founderList.body())
        assertEquals(setOf(a, b, c), ids(founderList.body()).toSet())

        val mgr = managerToken(founder.orgId, b)
        val mgrList = get("/api/v1/branches", bearer(mgr))
        assertEquals(200, mgrList.statusCode(), mgrList.body())
        assertEquals(listOf(b), ids(mgrList.body()))

        // employee cannot list at all
        val emp = employeeToken(founder.orgId, unitId(founder.token, a))
        assertEquals(403, get("/api/v1/branches", bearer(emp)).statusCode())
    }

    // --- branches: pagination ---------------------------------------------

    @Test
    fun `branches paginate through a stable cursor with no gaps or duplicates`() {
        val founder = registerFounder()
        val created = (1..5).map { branchId(founder.token, "Br-$it") }.toSet()

        val collected = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val q = "/api/v1/branches?limit=2" + (cursor?.let { "&cursor=$it" } ?: "")
            val res = get(q, bearer(founder.token))
            assertEquals(200, res.statusCode(), res.body())
            val page = items(res.body())
            assertTrue(page.size <= 2, "a page must never exceed the limit")
            collected += page.map { it["id"] as String }
            cursor = parse(res.body())["nextCursor"] as String?
            pages++
        } while (cursor != null && pages < 10)

        assertNull(cursor, "pagination must terminate")
        assertEquals(created, collected.toSet())
        assertEquals(collected.size, collected.toSet().size, "no id may appear twice across pages")
    }

    @Test
    fun `a malformed cursor is a 400`() {
        val founder = registerFounder()
        val res = get("/api/v1/branches?limit=2&cursor=abcd", bearer(founder.token))
        assertEquals(400, res.statusCode(), res.body())
        assertEquals("VALIDATION", parse(res.body())["code"])
    }

    // --- units: create scoping --------------------------------------------

    @Test
    fun `unit creation is confined by role and branch`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")

        // founder may create under any branch of the org
        assertEquals(201, createUnit(founder.token, a).statusCode())
        assertEquals(201, createUnit(founder.token, b).statusCode())

        // manager of A may create under A, but not under B (reported as 404, not 403 — no existence leak)
        val mgr = managerToken(founder.orgId, a)
        assertEquals(201, createUnit(mgr, a).statusCode())
        val crossBranch = createUnit(mgr, b)
        assertEquals(404, crossBranch.statusCode(), crossBranch.body())
        assertEquals("NOT_FOUND", parse(crossBranch.body())["code"])

        // employee may not create units
        val emp = employeeToken(founder.orgId, unitId(founder.token, a))
        assertEquals(403, createUnit(emp, a).statusCode())

        // a branch that does not exist is a 404
        assertEquals(404, createUnit(founder.token, UUID.randomUUID().toString()).statusCode())
    }

    // --- units: list scoping ----------------------------------------------

    @Test
    fun `unit listing respects branch scope and the branchId filter`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")
        val a1 = unitId(founder.token, a)
        val a2 = unitId(founder.token, a)
        val b1 = unitId(founder.token, b)

        // founder: all units, and the filter narrows to one branch
        assertEquals(setOf(a1, a2, b1), ids(get("/api/v1/units", bearer(founder.token)).body()).toSet())
        assertEquals(setOf(a1, a2), ids(get("/api/v1/units?branchId=$a", bearer(founder.token)).body()).toSet())

        // manager of A: only A's units; asking for B yields an empty page (scope ∩ filter)
        val mgr = managerToken(founder.orgId, a)
        assertEquals(setOf(a1, a2), ids(get("/api/v1/units", bearer(mgr)).body()).toSet())
        assertEquals(setOf(a1, a2), ids(get("/api/v1/units?branchId=$a", bearer(mgr)).body()).toSet())
        val foreign = get("/api/v1/units?branchId=$b", bearer(mgr))
        assertEquals(200, foreign.statusCode(), foreign.body())
        assertTrue(items(foreign.body()).isEmpty(), "a manager must not see another branch's units")

        // employee cannot list units
        val emp = employeeToken(founder.orgId, a1)
        assertEquals(403, get("/api/v1/units", bearer(emp)).statusCode())
    }

    // --- tenant isolation --------------------------------------------------

    @Test
    fun `one org's founder can neither see nor target another org's branches and units`() {
        val org1 = registerFounder()
        val branch1 = branchId(org1.token, "Org1-Branch")
        val unit1 = unitId(org1.token, branch1)

        val org2 = registerFounder()

        // org2 sees none of org1's branches or units
        val branches2 = get("/api/v1/branches", bearer(org2.token))
        assertFalse(ids(branches2.body()).contains(branch1))
        val units2 = get("/api/v1/units", bearer(org2.token))
        assertFalse(ids(units2.body()).contains(unit1))

        // org2 cannot create a unit under org1's branch (branch is invisible => 404)
        val cross = createUnit(org2.token, branch1)
        assertEquals(404, cross.statusCode(), cross.body())
        assertEquals("NOT_FOUND", parse(cross.body())["code"])
    }
}
