package uz.lebellion.schedule.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import uz.lebellion.schedule.service.ScheduleGenerator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Schedule CRUD (P2-3) over HTTP: role/branch scoping, DAILY-with-slots vs WEEKLY, request validation,
 * and the per-unit task-instance feed after a real generation pass. Reuses OrgManagementIT's setup style.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
    ],
)
@Testcontainers
class ScheduleManagementIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var appEncoder: JwtEncoder
    @Autowired lateinit var users: AppUserRepository
    @Autowired lateinit var generator: ScheduleGenerator

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
    private fun array(body: String): List<Map<String, Any?>> = mapper.readValue(body, List::class.java) as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun slotsOf(body: String): List<Map<String, Any?>> = parse(body)["slots"] as List<Map<String, Any?>>

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
            AppUser(organizationId = UUID.fromString(orgId), name = "Emp", role = Role.EMPLOYEE, unitId = UUID.fromString(unitId)),
        )
        return TestJwt.mint(appEncoder, subject = employee.id!!.toString(), orgId = orgId, role = "EMPLOYEE", tokenVersion = 0)
    }

    // --- domain helpers --------------------------------------------------

    private fun branchId(token: String): String {
        val res = post("/api/v1/branches", mapOf("name" to "Branch-${UUID.randomUUID()}"), bearer(token))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["id"] as String
    }

    private fun unitId(token: String, branchId: String): String {
        val res = post("/api/v1/branches/$branchId/units", mapOf("name" to "Unit-${UUID.randomUUID()}"), bearer(token))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["id"] as String
    }

    private fun templateId(token: String, unitId: String): String {
        val res = post("/api/v1/units/$unitId/templates", mapOf("name" to "Повар"), bearer(token))
        assertEquals(201, res.statusCode(), res.body())
        return parse(res.body())["id"] as String
    }

    private fun today(): String = LocalDate.now(ZoneId.of("Asia/Tashkent")).toString()

    private fun dailyBody(vararg slots: Map<String, Any?>): Map<String, Any?> = mapOf(
        "recurrence" to "DAILY",
        "anchorDate" to today(),
        "slotWindowMinutes" to 60,
        "slots" to slots.toList(),
    )

    private fun createSchedule(token: String, templateId: String, body: Map<String, Any?>): HttpResponse<String> =
        post("/api/v1/templates/$templateId/schedules", body, bearer(token))

    // --- schedules: scoping & shape ---------------------------------------

    @Test
    fun `founder creates a daily schedule with slots, manager is branch-scoped, employee is refused`() {
        val founder = registerFounder()
        val a = branchId(founder.token)
        val b = branchId(founder.token)
        val unitA = unitId(founder.token, a)
        val templateA = templateId(founder.token, unitA)

        val created = createSchedule(
            founder.token,
            templateA,
            dailyBody(
                mapOf("slotTime" to "11:00", "sortOrder" to 0),
                mapOf("slotTime" to "23:00", "photoRequired" to false, "sortOrder" to 1),
            ),
        )
        assertEquals(201, created.statusCode(), created.body())
        val body = parse(created.body())
        assertEquals("DAILY", body["recurrence"])
        assertEquals(today(), body["anchorDate"])
        assertEquals(unitA, body["unitId"])
        assertEquals(2, slotsOf(created.body()).size)

        // manager of A may create under A's template...
        val mgrA = managerToken(founder.orgId, a)
        assertEquals(201, createSchedule(mgrA, templateA, dailyBody(mapOf("slotTime" to "09:00"))).statusCode())

        // ...but a manager of B cannot (template's unit is outside scope => 404)
        val mgrB = managerToken(founder.orgId, b)
        val cross = createSchedule(mgrB, templateA, dailyBody(mapOf("slotTime" to "09:00")))
        assertEquals(404, cross.statusCode(), cross.body())
        assertEquals("NOT_FOUND", parse(cross.body())["code"])

        // employee may not create schedules
        val emp = employeeToken(founder.orgId, unitA)
        assertEquals(403, createSchedule(emp, templateA, dailyBody(mapOf("slotTime" to "09:00"))).statusCode())

        // listing returns both founder+manager schedules
        val listed = get("/api/v1/templates/$templateA/schedules", bearer(founder.token))
        assertEquals(200, listed.statusCode(), listed.body())
        assertEquals(2, array(listed.body()).size)
    }

    @Test
    fun `a weekly schedule has no slots and a fixed weekly cadence`() {
        val founder = registerFounder()
        val template = templateId(founder.token, unitId(founder.token, branchId(founder.token)))

        val res = createSchedule(founder.token, template, mapOf("recurrence" to "WEEKLY", "intervalDays" to 3))
        assertEquals(201, res.statusCode(), res.body())
        val body = parse(res.body())
        assertEquals("WEEKLY", body["recurrence"])
        assertEquals(1, (body["intervalDays"] as Number).toInt(), "interval_days forced to 1 for WEEKLY")
        assertTrue(slotsOf(res.body()).isEmpty())
    }

    @Test
    fun `daily schedule validation rejects a missing anchor, no slots, or a non-positive window`() {
        val founder = registerFounder()
        val template = templateId(founder.token, unitId(founder.token, branchId(founder.token)))

        // missing anchorDate
        assertEquals(
            400,
            createSchedule(founder.token, template, mapOf("recurrence" to "DAILY", "slots" to listOf(mapOf("slotTime" to "10:00")))).statusCode(),
        )
        // no slots
        assertEquals(
            400,
            createSchedule(founder.token, template, mapOf("recurrence" to "DAILY", "anchorDate" to today())).statusCode(),
        )
        // window must be >= 1
        assertEquals(
            400,
            createSchedule(founder.token, template, mapOf("recurrence" to "DAILY", "anchorDate" to today(), "slotWindowMinutes" to 0, "slots" to listOf(mapOf("slotTime" to "10:00")))).statusCode(),
        )
    }

    // --- task instances: generation + feed --------------------------------

    @Test
    fun `task instances are generated and listed for a unit, with scoping enforced`() {
        val founder = registerFounder()
        val branch = branchId(founder.token)
        val unit = unitId(founder.token, branch)
        val template = templateId(founder.token, unit)

        // an ad-hoc PHOTO item so the template has a line to schedule
        post("/api/v1/templates/$template/items", mapOf("type" to "PHOTO", "titleRu" to "Мойка", "titleUz" to "Yuvish"), bearer(founder.token))
        assertEquals(201, createSchedule(founder.token, template, dailyBody(mapOf("slotTime" to "12:00"))).statusCode())

        // run a real generation pass, then read the unit's feed
        generator.generate()
        val feed = get("/api/v1/units/$unit/task-instances", bearer(founder.token))
        assertEquals(200, feed.statusCode(), feed.body())
        val instances = array(feed.body())
        assertTrue(instances.isNotEmpty(), "daily schedule from today should yield upcoming instances")
        assertTrue(instances.all { it["status"] == "PENDING" })
        assertTrue(instances.all { it["unitId"] == unit })

        // employee cannot read the feed
        val emp = employeeToken(founder.orgId, unit)
        assertEquals(403, get("/api/v1/units/$unit/task-instances", bearer(emp)).statusCode())

        // another org cannot read this unit's feed (unit invisible => 404)
        val org2 = registerFounder()
        assertEquals(404, get("/api/v1/units/$unit/task-instances", bearer(org2.token)).statusCode())
    }

    @Test
    fun `another org cannot create a schedule on a foreign template`() {
        val org1 = registerFounder()
        val template1 = templateId(org1.token, unitId(org1.token, branchId(org1.token)))

        val org2 = registerFounder()
        val cross = createSchedule(org2.token, template1, dailyBody(mapOf("slotTime" to "10:00")))
        assertEquals(404, cross.statusCode(), cross.body())
        assertEquals("NOT_FOUND", parse(cross.body())["code"])
    }
}
