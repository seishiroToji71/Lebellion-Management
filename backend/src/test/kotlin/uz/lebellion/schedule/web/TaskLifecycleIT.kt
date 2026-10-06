package uz.lebellion.schedule.web

import org.junit.jupiter.api.Assertions.assertEquals
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
import uz.lebellion.schedule.domain.TaskInstance
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.repo.TaskInstanceRepository
import uz.lebellion.schedule.service.MissedSweeper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/**
 * Task lifecycle (P2-4): the minimal submit transition and its guards, plus the MISSED sweeper. Jobs are
 * disabled; the sweeper is invoked explicitly. Instances are seeded directly with real FK ids from the
 * HTTP-built org so we can place a task in any status / due time.
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
class TaskLifecycleIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var appEncoder: JwtEncoder
    @Autowired lateinit var users: AppUserRepository
    @Autowired lateinit var taskInstances: TaskInstanceRepository
    @Autowired lateinit var sweeper: MissedSweeper
    @Autowired lateinit var jdbc: JdbcTemplate

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun send(method: String, path: String, body: Map<String, Any?>?, headers: Map<String, String>): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
        val b = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path")).method(method, publisher)
        if (body != null) b.header("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: Map<String, Any?>, headers: Map<String, String>) = send("POST", path, body, headers)

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    private fun bearer(token: String) = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")

    private data class Ctx(val orgId: String, val token: String, val unit: String, val template: String, val item: String, val schedule: String)

    private fun setup(): Ctx {
        val reg = post(
            "/api/v1/auth/register",
            mapOf("organizationName" to "Org-${UUID.randomUUID()}", "fullName" to "F", "email" to "f-${UUID.randomUUID()}@e.com", "password" to "sup3rsecret!"),
            mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to "device-1"),
        )
        assertEquals(201, reg.statusCode(), reg.body())
        val body = parse(reg.body())
        @Suppress("UNCHECKED_CAST")
        val orgId = (body["user"] as Map<String, Any?>)["organizationId"] as String
        val token = body["accessToken"] as String
        val branch = parse(post("/api/v1/branches", mapOf("name" to "B"), bearer(token)).body())["id"] as String
        val unit = parse(post("/api/v1/branches/$branch/units", mapOf("name" to "U"), bearer(token)).body())["id"] as String
        val template = parse(post("/api/v1/units/$unit/templates", mapOf("name" to "T"), bearer(token)).body())["id"] as String
        val item = parse(post("/api/v1/templates/$template/items", mapOf("type" to "PHOTO", "titleRu" to "r", "titleUz" to "u"), bearer(token)).body())["id"] as String
        val schedule = parse(
            post("/api/v1/templates/$template/schedules", mapOf("recurrence" to "WEEKLY"), bearer(token)).body(),
        )["id"] as String
        return Ctx(orgId, token, unit, template, item, schedule)
    }

    private fun employeeToken(orgId: String, unitId: String): String {
        val e = users.save(AppUser(organizationId = UUID.fromString(orgId), name = "E", role = Role.EMPLOYEE, unitId = UUID.fromString(unitId)))
        return TestJwt.mint(appEncoder, subject = e.id!!.toString(), orgId = orgId, role = "EMPLOYEE", tokenVersion = 0)
    }

    private fun seedTask(c: Ctx, due: Instant, status: TaskStatus): UUID = taskInstances.save(
        TaskInstance(
            organizationId = UUID.fromString(c.orgId),
            scheduleId = UUID.fromString(c.schedule),
            templateId = UUID.fromString(c.template),
            itemId = UUID.fromString(c.item),
            unitId = UUID.fromString(c.unit),
            periodKey = "s-${UUID.randomUUID()}",
            dueAt = due,
            photoRequired = false,
            status = status,
        ),
    ).id!!

    private fun submit(token: String, taskId: UUID) = send("POST", "/api/v1/task-instances/$taskId/submit", null, bearer(token))

    private fun countOutbox(orgId: String, type: String): Int =
        jdbc.queryForObject("select count(*) from notification_outbox where organization_id = CAST(? AS uuid) and type = ?", Int::class.java, orgId, type)!!

    private fun countAudit(orgId: String, event: String): Int =
        jdbc.queryForObject("select count(*) from audit_log where organization_id = CAST(? AS uuid) and event_type = ?", Int::class.java, orgId, event)!!

    // --- submit transitions ----------------------------------------------

    @Test
    fun `an employee submits a pending task`() {
        val c = setup()
        val task = seedTask(c, Instant.now().plusSeconds(3600), TaskStatus.PENDING)
        val emp = employeeToken(c.orgId, c.unit)

        val res = submit(emp, task)
        assertEquals(200, res.statusCode(), res.body())
        assertEquals("SUBMITTED", parse(res.body())["status"])
        assertEquals(TaskStatus.SUBMITTED, taskInstances.findById(task).get().status)
        assertTrue(countOutbox(c.orgId, "TASK_SUBMITTED") >= 1)
    }

    @Test
    fun `submitting a cancelled task is 409 TASK_CANCELLED and the attempt is recorded`() {
        val c = setup()
        val task = seedTask(c, Instant.now().plusSeconds(3600), TaskStatus.CANCELLED)
        val emp = employeeToken(c.orgId, c.unit)

        val res = submit(emp, task)
        assertEquals(409, res.statusCode(), res.body())
        assertEquals("TASK_CANCELLED", parse(res.body())["code"])
        assertEquals(TaskStatus.CANCELLED, taskInstances.findById(task).get().status, "status unchanged")
        assertTrue(countAudit(c.orgId, "TASK_SUBMIT_REJECTED") >= 1, "the attempt is preserved, not lost to a 404")
    }

    @Test
    fun `the sweeper marks overdue tasks MISSED, flags the lead, and then submission is 409 TASK_NOT_OPEN`() {
        val c = setup()
        // make the unit's employee its lead, so the MISSED notification carries a lead
        val lead = users.save(AppUser(organizationId = UUID.fromString(c.orgId), name = "Lead", role = Role.EMPLOYEE, unitId = UUID.fromString(c.unit)))
        val leadId = lead.id!!.toString()
        assertEquals(
            200,
            send("PUT", "/api/v1/units/${c.unit}/lead", mapOf("leadEmployeeId" to leadId), bearer(c.token)).statusCode(),
        )

        val task = seedTask(c, Instant.now().minusSeconds(3600), TaskStatus.PENDING)
        assertEquals(1, sweeper.sweep())
        assertEquals(TaskStatus.MISSED, taskInstances.findById(task).get().status)
        val missedWithLead = jdbc.queryForObject(
            "select count(*) from notification_outbox where organization_id = CAST(? AS uuid) and type = 'TASK_MISSED' and payload->>'leadUserId' = ?",
            Int::class.java, c.orgId, leadId,
        )!!
        assertTrue(missedWithLead >= 1, "missed task flags the unit's effective lead")

        val emp = employeeToken(c.orgId, c.unit)
        val res = submit(emp, task)
        assertEquals(409, res.statusCode(), res.body())
        assertEquals("TASK_NOT_OPEN", parse(res.body())["code"])
    }

    // --- scoping ----------------------------------------------------------

    @Test
    fun `submission is confined to the task's unit and tenant`() {
        val c = setup()
        val task = seedTask(c, Instant.now().plusSeconds(3600), TaskStatus.PENDING)

        // an employee of a different unit cannot submit (404 — no existence leak)
        val otherUnit = parse(
            post("/api/v1/branches/${parse(post("/api/v1/branches", mapOf("name" to "B2"), bearer(c.token)).body())["id"]}/units", mapOf("name" to "U2"), bearer(c.token)).body(),
        )["id"] as String
        val empElsewhere = employeeToken(c.orgId, otherUnit)
        assertEquals(404, submit(empElsewhere, task).statusCode())

        // another org cannot submit
        val other = setup()
        assertEquals(404, submit(other.token, task).statusCode())
    }
}
