package uz.lebellion.schedule.web

import org.junit.jupiter.api.Assertions.assertEquals
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
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.repo.TaskInstanceRepository
import uz.lebellion.schedule.service.ScheduleGenerator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Schedule edit / deactivate with regenerate-forward (P2-4). Jobs are disabled; generation is triggered
 * explicitly. Asserts the cancel-forward (future PENDING -> CANCELLED), regeneration reusing the same
 * period_key (proving the partial unique index), and role/branch scoping.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
        "lebellion.schedule.generation-enabled=false",
        "lebellion.schedule.missed-sweep-enabled=false",
    ],
)
@Testcontainers
class ScheduleAdminIT {

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
    @Autowired lateinit var taskInstances: TaskInstanceRepository

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun send(method: String, path: String, body: Map<String, Any?>?, headers: Map<String, String>): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
        val b = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path")).method(method, publisher)
        if (body != null) b.header("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: Map<String, Any?>, headers: Map<String, String>) = send("POST", path, body, headers)
    private fun put(path: String, body: Map<String, Any?>, headers: Map<String, String>) = send("PUT", path, body, headers)

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    private data class Founder(val token: String, val orgId: String)

    private fun bearer(token: String) = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")

    private fun registerFounder(): Founder {
        val res = post(
            "/api/v1/auth/register",
            mapOf("organizationName" to "Org-${UUID.randomUUID()}", "fullName" to "F", "email" to "f-${UUID.randomUUID()}@e.com", "password" to "sup3rsecret!"),
            mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to "device-1"),
        )
        assertEquals(201, res.statusCode(), res.body())
        val body = parse(res.body())
        @Suppress("UNCHECKED_CAST")
        val user = body["user"] as Map<String, Any?>
        return Founder(body["accessToken"] as String, user["organizationId"] as String)
    }

    private fun managerToken(orgId: String, branchId: String): String {
        val m = users.save(AppUser(organizationId = UUID.fromString(orgId), name = "M", role = Role.BRANCH_MANAGER, email = "m-${UUID.randomUUID()}@e.com", branchId = UUID.fromString(branchId), passwordHash = "x"))
        return TestJwt.mint(appEncoder, subject = m.id!!.toString(), orgId = orgId, role = "BRANCH_MANAGER", tokenVersion = 0)
    }

    private fun employeeToken(orgId: String, unitId: String): String {
        val e = users.save(AppUser(organizationId = UUID.fromString(orgId), name = "E", role = Role.EMPLOYEE, unitId = UUID.fromString(unitId)))
        return TestJwt.mint(appEncoder, subject = e.id!!.toString(), orgId = orgId, role = "EMPLOYEE", tokenVersion = 0)
    }

    private fun branchId(token: String): String {
        val r = post("/api/v1/branches", mapOf("name" to "B-${UUID.randomUUID()}"), bearer(token)); return parse(r.body())["id"] as String
    }

    private fun unitId(token: String, branchId: String): String {
        val r = post("/api/v1/branches/$branchId/units", mapOf("name" to "U-${UUID.randomUUID()}"), bearer(token)); return parse(r.body())["id"] as String
    }

    private fun templateId(token: String, unitId: String): String {
        val r = post("/api/v1/units/$unitId/templates", mapOf("name" to "T"), bearer(token)); return parse(r.body())["id"] as String
    }

    private fun addItem(token: String, templateId: String) {
        assertEquals(201, post("/api/v1/templates/$templateId/items", mapOf("type" to "PHOTO", "titleRu" to "r", "titleUz" to "u"), bearer(token)).statusCode())
    }

    private fun today(): String = LocalDate.now(ZoneId.of("Asia/Tashkent")).toString()

    private fun createDailySchedule(token: String, templateId: String, window: Int = 60): String {
        val body = mapOf("recurrence" to "DAILY", "anchorDate" to today(), "slotWindowMinutes" to window, "slots" to listOf(mapOf("slotTime" to "23:00")))
        val r = post("/api/v1/templates/$templateId/schedules", body, bearer(token))
        assertEquals(201, r.statusCode(), r.body())
        return parse(r.body())["id"] as String
    }

    // --- edit: cancel-forward + regenerate (same period_key coexists) ----

    @Test
    fun `editing a schedule cancels future PENDING and regenerates reusing the same period_key`() {
        val founder = registerFounder()
        val template = templateId(founder.token, unitId(founder.token, branchId(founder.token)))
        addItem(founder.token, template)
        val schedule = createDailySchedule(founder.token, template, window = 60)

        generator.generate()
        val beforeKeys = taskInstances.findByScheduleId(UUID.fromString(schedule)).map { it.periodKey }.toSet()
        assertTrue(beforeKeys.isNotEmpty())

        // edit: widen the window to 120 (same slot -> same period_keys)
        val edit = put(
            "/api/v1/schedules/$schedule",
            mapOf("anchorDate" to today(), "slotWindowMinutes" to 120, "active" to true, "slots" to listOf(mapOf("slotTime" to "23:00"))),
            bearer(founder.token),
        )
        assertEquals(200, edit.statusCode(), edit.body())

        val all = taskInstances.findByScheduleId(UUID.fromString(schedule))
        val cancelled = all.filter { it.status == TaskStatus.CANCELLED }
        val pending = all.filter { it.status == TaskStatus.PENDING }
        assertTrue(cancelled.isNotEmpty(), "former PENDING were cancelled")
        assertTrue(pending.isNotEmpty(), "new PENDING were regenerated")

        // the same (item, period_key) now carries both a CANCELLED and a fresh PENDING row
        val sharedKey = cancelled.first().let { c -> c.itemId to c.periodKey }
        assertTrue(pending.any { it.itemId == sharedKey.first && it.periodKey == sharedKey.second }, "period_key reused alongside the cancelled row")

        // the new PENDING reflects the widened window (scheduled_at + 120m)
        val sample = pending.first { it.scheduledAt != null }
        assertEquals(120L, java.time.Duration.between(sample.scheduledAt, sample.dueAt).toMinutes())
    }

    // --- deactivate: cancel-forward, no regeneration ---------------------

    @Test
    fun `deactivating a schedule cancels its future PENDING and stops the feed`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))
        val template = templateId(founder.token, unit)
        addItem(founder.token, template)
        val schedule = createDailySchedule(founder.token, template)

        generator.generate()
        assertTrue(taskInstances.findByScheduleId(UUID.fromString(schedule)).any { it.status == TaskStatus.PENDING })

        val res = send("POST", "/api/v1/schedules/$schedule/deactivate", null, bearer(founder.token))
        assertEquals(200, res.statusCode(), res.body())
        assertEquals(false, parse(res.body())["active"])

        val all = taskInstances.findByScheduleId(UUID.fromString(schedule))
        assertTrue(all.isNotEmpty() && all.all { it.status == TaskStatus.CANCELLED }, "all future PENDING cancelled")

        // the feed hides CANCELLED
        val feed = send("GET", "/api/v1/units/$unit/task-instances?from=2020-01-01T00:00:00Z&to=2100-01-01T00:00:00Z", null, bearer(founder.token))
        assertEquals(200, feed.statusCode(), feed.body())
        @Suppress("UNCHECKED_CAST")
        val items = mapper.readValue(feed.body(), List::class.java) as List<Map<String, Any?>>
        assertTrue(items.isEmpty(), "cancelled instances must not appear in the feed")
    }

    // --- scoping ----------------------------------------------------------

    @Test
    fun `edit and deactivate are confined by role and branch`() {
        val founder = registerFounder()
        val a = branchId(founder.token)
        val unitA = unitId(founder.token, a)
        val templateA = templateId(founder.token, unitA)
        addItem(founder.token, templateA)
        val schedule = createDailySchedule(founder.token, templateA)

        // a manager of a DIFFERENT branch cannot touch it (404)
        val mgrB = managerToken(founder.orgId, branchId(founder.token))
        assertEquals(404, send("POST", "/api/v1/schedules/$schedule/deactivate", null, bearer(mgrB)).statusCode())

        // an employee cannot edit
        val emp = employeeToken(founder.orgId, unitA)
        assertEquals(403, put("/api/v1/schedules/$schedule", mapOf("anchorDate" to today(), "slots" to listOf(mapOf("slotTime" to "10:00"))), bearer(emp)).statusCode())

        // another org cannot see it
        val org2 = registerFounder()
        assertEquals(404, send("POST", "/api/v1/schedules/$schedule/deactivate", null, bearer(org2.token)).statusCode())
    }
}
