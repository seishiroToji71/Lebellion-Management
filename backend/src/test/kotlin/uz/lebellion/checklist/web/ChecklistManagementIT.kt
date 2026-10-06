package uz.lebellion.checklist.web

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
 * Phase 2, block P2-1: the criterion library + checklist templates & items. Covers role scoping
 * (FOUNDER vs BRANCH_MANAGER vs EMPLOYEE), per-branch confinement of managers, tenant isolation,
 * linked-vs-ad-hoc items (text resolved from the library, no duplication; overridable config), item
 * ordering, and request validation. Reuses the register/seed/mint pattern of OrgManagementIT.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
    ],
)
@Testcontainers
class ChecklistManagementIT {

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

    @Suppress("UNCHECKED_CAST")
    private fun array(body: String): List<Map<String, Any?>> = mapper.readValue(body, List::class.java) as List<Map<String, Any?>>

    private fun ids(body: String): List<String> = items(body).map { it["id"] as String }

    private fun int(value: Any?): Int = (value as Number).toInt()

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

    // --- org helpers (via the existing org endpoints) --------------------

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

    // --- checklist helpers -----------------------------------------------

    private fun criterionBody(
        type: String = "PHOTO",
        titleRu: String = "Чистота холодильника",
        titleUz: String = "Muzlatgich tozaligi",
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = mapOf("type" to type, "titleRu" to titleRu, "titleUz" to titleUz) + extra

    private fun createCriterion(token: String, body: Map<String, Any?> = criterionBody()): HttpResponse<String> =
        post("/api/v1/criteria", body, bearer(token))

    private fun criterionId(token: String, body: Map<String, Any?> = criterionBody()): String {
        val res = createCriterion(token, body)
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["id"] as String
    }

    private fun createTemplate(token: String, unitId: String, name: String = "Повар мангалщик"): HttpResponse<String> =
        post("/api/v1/units/$unitId/templates", mapOf("name" to name), bearer(token))

    private fun templateId(token: String, unitId: String, name: String = "Повар мангалщик"): String {
        val res = createTemplate(token, unitId, name)
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["id"] as String
    }

    private fun createItem(token: String, templateId: String, body: Map<String, Any?>): HttpResponse<String> =
        post("/api/v1/templates/$templateId/items", body, bearer(token))

    // --- criteria: role scoping -------------------------------------------

    @Test
    fun `founder manages the criterion library, a manager may read but not create, an employee cannot read`() {
        val founder = registerFounder()
        val branch = branchId(founder.token, "HQ")
        val unit = unitId(founder.token, branch)

        val created = createCriterion(founder.token)
        assertEquals(201, created.statusCode(), created.body())
        val body = parse(created.body())
        assertNotNull(body["id"])
        assertEquals("PHOTO", body["type"])
        assertEquals("Чистота холодильника", body["titleRu"])
        assertEquals(6, int(body["dhashThreshold"]))

        // a manager may read the library (to compose templates) but not create entries
        val mgr = managerToken(founder.orgId, branch)
        assertEquals(403, createCriterion(mgr).statusCode())
        val mgrList = get("/api/v1/criteria", bearer(mgr))
        assertEquals(200, mgrList.statusCode(), mgrList.body())
        assertEquals(1, items(mgrList.body()).size)

        // an employee may not read the library at all
        val emp = employeeToken(founder.orgId, unit)
        assertEquals(403, get("/api/v1/criteria", bearer(emp)).statusCode())
    }

    // --- templates: role & branch scoping; many per unit ------------------

    @Test
    fun `template creation is confined by role and branch, and a unit may own several templates`() {
        val founder = registerFounder()
        val a = branchId(founder.token, "A")
        val b = branchId(founder.token, "B")
        val unitA = unitId(founder.token, a)
        val unitB = unitId(founder.token, b)

        // founder may create several templates under any unit
        assertEquals(201, createTemplate(founder.token, unitA, "Повар").statusCode())
        assertEquals(201, createTemplate(founder.token, unitA, "Мангалщик").statusCode())
        assertEquals(2, items(get("/api/v1/units/$unitA/templates", bearer(founder.token)).body()).size)

        // manager of A may create under A's unit, but not under B's (reported as 404 — no existence leak)
        val mgr = managerToken(founder.orgId, a)
        assertEquals(201, createTemplate(mgr, unitA).statusCode())
        val crossBranch = createTemplate(mgr, unitB)
        assertEquals(404, crossBranch.statusCode(), crossBranch.body())
        assertEquals("NOT_FOUND", parse(crossBranch.body())["code"])

        // employee may not create templates
        val emp = employeeToken(founder.orgId, unitA)
        assertEquals(403, createTemplate(emp, unitA).statusCode())

        // a unit that does not exist is a 404
        assertEquals(404, createTemplate(founder.token, UUID.randomUUID().toString()).statusCode())
    }

    // --- items: linked criterion resolves text and copies overridable config

    @Test
    fun `a linked item resolves its text from the library and inherits overridable config`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))
        val template = templateId(founder.token, unit)
        val criterion = criterionId(
            founder.token,
            criterionBody(extra = mapOf("points" to 10, "staticScene" to true, "photoRequired" to true)),
        )

        // linked with no overrides: text + config come from the criterion, inline text is not stored
        val linked = createItem(founder.token, template, mapOf("criterionId" to criterion))
        assertEquals(201, linked.statusCode(), linked.body())
        val b1 = parse(linked.body())
        assertEquals(criterion, b1["criterionId"])
        assertEquals("PHOTO", b1["type"])
        assertEquals("Чистота холодильника", b1["titleRu"], "title must resolve from the library")
        assertEquals(10, int(b1["points"]))
        assertEquals(true, b1["staticScene"])
        assertEquals(true, b1["photoRequired"])

        // linked WITH overrides: config overridden, text still from the library
        val overridden = createItem(
            founder.token,
            template,
            mapOf("criterionId" to criterion, "points" to 5, "staticScene" to false),
        )
        assertEquals(201, overridden.statusCode(), overridden.body())
        val b2 = parse(overridden.body())
        assertEquals(5, int(b2["points"]))
        assertEquals(false, b2["staticScene"])
        assertEquals("Чистота холодильника", b2["titleRu"])
    }

    // --- items: ad-hoc + validation ---------------------------------------

    @Test
    fun `an ad-hoc item stores inline text, and a missing type or title is a 400`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))
        val template = templateId(founder.token, unit)

        val adhoc = createItem(
            founder.token,
            template,
            mapOf("type" to "MANUAL", "titleRu" to "Внешний вид", "titleUz" to "Tashqi ko'rinish", "points" to 20),
        )
        assertEquals(201, adhoc.statusCode(), adhoc.body())
        val body = parse(adhoc.body())
        assertNull(body["criterionId"])
        assertEquals("MANUAL", body["type"])
        assertEquals("Внешний вид", body["titleRu"])
        assertEquals(20, int(body["points"]))

        // no criterion and no type
        assertEquals(400, createItem(founder.token, template, mapOf("titleRu" to "x", "titleUz" to "x")).statusCode())
        // no criterion, type present, but titles missing
        assertEquals(400, createItem(founder.token, template, mapOf("type" to "PHOTO")).statusCode())
    }

    @Test
    fun `items are listed in sortOrder`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))
        val template = templateId(founder.token, unit)

        fun adhoc(title: String, order: Int) = createItem(
            founder.token,
            template,
            mapOf("type" to "PHOTO", "titleRu" to title, "titleUz" to title, "sortOrder" to order),
        ).also { assertEquals(201, it.statusCode(), it.body()) }

        adhoc("second", 2)
        adhoc("zeroth", 0)
        adhoc("first", 1)

        val listed = get("/api/v1/templates/$template/items", bearer(founder.token))
        assertEquals(200, listed.statusCode(), listed.body())
        val titles = array(listed.body()).map { it["titleRu"] as String }
        assertEquals(listOf("zeroth", "first", "second"), titles)
    }

    @Test
    fun `negative points and an out-of-range dhash threshold are rejected`() {
        val founder = registerFounder()
        assertEquals(400, createCriterion(founder.token, criterionBody(extra = mapOf("points" to -1))).statusCode())
        assertEquals(400, createCriterion(founder.token, criterionBody(extra = mapOf("dhashThreshold" to 65))).statusCode())
    }

    // --- tenant isolation --------------------------------------------------

    @Test
    fun `an organization cannot see or target another organization's criteria, templates or items`() {
        val org1 = registerFounder()
        val unit1 = unitId(org1.token, branchId(org1.token))
        val template1 = templateId(org1.token, unit1)
        val criterion1 = criterionId(org1.token)
        assertEquals(201, createItem(org1.token, template1, mapOf("criterionId" to criterion1)).statusCode())

        val org2 = registerFounder()

        // org2's library does not contain org1's criterion
        val lib2 = get("/api/v1/criteria", bearer(org2.token))
        assertTrue(items(lib2.body()).isEmpty(), "org2 must not see org1's criteria")

        // org2 cannot list/create templates under org1's unit (unit invisible => 404)
        assertEquals(404, get("/api/v1/units/$unit1/templates", bearer(org2.token)).statusCode())
        assertEquals(404, createTemplate(org2.token, unit1).statusCode())

        // org2 cannot add items to org1's template (template invisible => 404)
        val unit2 = unitId(org2.token, branchId(org2.token))
        val template2 = templateId(org2.token, unit2)
        assertEquals(404, createItem(org2.token, template1, mapOf("criterionId" to criterion1)).statusCode())

        // org2 cannot link org1's criterion into its own template (criterion invisible => 404)
        val cross = createItem(org2.token, template2, mapOf("criterionId" to criterion1))
        assertEquals(404, cross.statusCode(), cross.body())
        assertEquals("NOT_FOUND", parse(cross.body())["code"])
    }

    // --- pagination (reuses the shared keyset cursor) ---------------------

    @Test
    fun `criteria paginate through a stable cursor`() {
        val founder = registerFounder()
        val created = (1..5).map { criterionId(founder.token, criterionBody(titleRu = "C-$it")) }.toSet()

        val collected = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val q = "/api/v1/criteria?limit=2" + (cursor?.let { "&cursor=$it" } ?: "")
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
}
