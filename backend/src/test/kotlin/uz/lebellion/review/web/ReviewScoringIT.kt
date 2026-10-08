package uz.lebellion.review.web

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
import uz.lebellion.support.MultipartBody
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Review + scoring (P2-6): accept closes the zone, reject makes the task resubmittable, can_review and
 * self-review rules, FOUNDER override, zone progress N of M, MANUAL FULL/PARTIAL/ZERO, and NUMERIC bands
 * [lower, upper) including "0 in the best band" for a lower-is-better metric.
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
class ReviewScoringIT {

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
    @Autowired lateinit var jdbc: JdbcTemplate

    private val http: HttpClient = HttpClient.newHttpClient()

    // --- HTTP -------------------------------------------------------------

    private fun send(method: String, path: String, body: Map<String, Any?>?, headers: Map<String, String>): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
        val b = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path")).method(method, publisher)
        if (body != null) b.header("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: Map<String, Any?>, headers: Map<String, String>) = send("POST", path, body, headers)
    private fun bearer(token: String) = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    private fun pngImage(seed: Int): ByteArray {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) for (x in 0 until 64) {
            val v = ((x + seed * 17) * 3) and 0xFF; img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out); return out.toByteArray()
    }

    private fun submitPhoto(token: String, taskId: UUID, seed: Int, helperIds: List<String> = emptyList()): HttpResponse<String> {
        val parts = mutableListOf<MultipartBody.Part>(MultipartBody.Field("answer", "true"))
        helperIds.forEach { parts += MultipartBody.Field("helperUserIds", it) }
        parts += MultipartBody.FilePart("photo", "p.png", "image/png", pngImage(seed))
        val built = MultipartBody.build(parts)
        return http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/api/v1/task-instances/$taskId/submit"))
                .header("Content-Type", built.contentType).header("X-App-Version", "1.4.0").header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofByteArray(built.body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun submitNoPhoto(token: String, taskId: UUID): HttpResponse<String> {
        val built = MultipartBody.build(listOf(MultipartBody.Field("answer", "true")))
        return http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/api/v1/task-instances/$taskId/submit"))
                .header("Content-Type", built.contentType).header("X-App-Version", "1.4.0").header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofByteArray(built.body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun review(token: String, submissionId: String, decision: String, reason: String? = null): HttpResponse<String> =
        post("/api/v1/submissions/$submissionId/review", mapOf("decision" to decision, "reason" to reason), bearer(token))

    // --- setup ------------------------------------------------------------

    private data class Ctx(val orgId: String, val token: String, val branch: String, val unit: String, val template: String, val schedule: String)

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
        val schedule = parse(post("/api/v1/templates/$template/schedules", mapOf("recurrence" to "WEEKLY"), bearer(token)).body())["id"] as String
        return Ctx(orgId, token, branch, unit, template, schedule)
    }

    private fun item(c: Ctx, type: String, points: Int = 0, staticScene: Boolean = false): String = parse(
        post(
            "/api/v1/templates/${c.template}/items",
            mapOf("type" to type, "titleRu" to "r", "titleUz" to "u", "points" to points, "photoRequired" to (type == "PHOTO"), "staticScene" to staticScene),
            bearer(c.token),
        ).body(),
    )["id"] as String

    private fun seedTask(c: Ctx, itemId: String, periodKey: String = "p-${UUID.randomUUID()}", photoRequired: Boolean = true): UUID = taskInstances.save(
        TaskInstance(
            organizationId = UUID.fromString(c.orgId),
            scheduleId = UUID.fromString(c.schedule),
            templateId = UUID.fromString(c.template),
            itemId = UUID.fromString(itemId),
            unitId = UUID.fromString(c.unit),
            periodKey = periodKey,
            dueAt = Instant.now().plusSeconds(3600),
            photoRequired = photoRequired,
            status = TaskStatus.PENDING,
        ),
    ).id!!

    private fun employee(c: Ctx, canReview: Boolean = false, canScore: Boolean = false): Pair<String, String> {
        val e = users.save(
            AppUser(organizationId = UUID.fromString(c.orgId), name = "E-${UUID.randomUUID()}", role = Role.EMPLOYEE, unitId = UUID.fromString(c.unit), canReview = canReview, canScore = canScore),
        )
        val id = e.id!!.toString()
        return id to TestJwt.mint(appEncoder, subject = id, orgId = c.orgId, role = "EMPLOYEE", tokenVersion = 0)
    }

    private fun setLead(c: Ctx, employeeId: String) {
        assertEquals(200, send("PUT", "/api/v1/units/${c.unit}/lead", mapOf("leadEmployeeId" to employeeId), bearer(c.token)).statusCode())
    }

    private fun auditCount(orgId: String, event: String): Int =
        jdbc.queryForObject("select count(*) from audit_log where organization_id = CAST(? AS uuid) and event_type = ?", Int::class.java, orgId, event)!!

    // --- review: accept closes the zone -----------------------------------

    @Test
    fun `accept closes the zone and a reject makes the task resubmittable`() {
        val c = setup()
        val it = item(c, "PHOTO")
        val task = seedTask(c, it)
        val (_, empTok) = employee(c)

        val sub = submitPhoto(empTok, task, seed = 1)
        assertEquals(200, sub.statusCode(), sub.body())
        val submissionId = parse(sub.body())["id"] as String

        // the FOUNDER rejects -> task back to PENDING, submission REJECTED
        val rej = review(c.token, submissionId, "REJECTED", reason = "blurry")
        assertEquals(200, rej.statusCode(), rej.body())
        assertEquals("REJECTED", parse(rej.body())["submissionStatus"])
        assertEquals("PENDING", parse(rej.body())["taskStatus"])
        assertTrue(auditCount(c.orgId, "REVIEW_REJECTED") >= 1)

        // resubmit (a different photo) is accepted now that the task is open again
        val resub = submitPhoto(empTok, task, seed = 2)
        assertEquals(200, resub.statusCode(), resub.body())
        val sub2 = parse(resub.body())["id"] as String

        // accept closes the zone
        val acc = review(c.token, sub2, "ACCEPTED")
        assertEquals(200, acc.statusCode(), acc.body())
        assertEquals("ACCEPTED", parse(acc.body())["submissionStatus"])
        assertEquals("DONE", parse(acc.body())["taskStatus"])
        assertEquals(TaskStatus.DONE, taskInstances.findById(task).get().status)
        assertTrue(auditCount(c.orgId, "REVIEW_ACCEPTED") >= 1)
    }

    // --- review permissions + self-review ---------------------------------

    @Test
    fun `can_review is required, the submitter and tagged helpers cannot review, a flag-holder can`() {
        val c = setup()
        val it = item(c, "PHOTO")
        val task = seedTask(c, it)
        // submitter and helper both HOLD can_review, so only the self-review rule should block them
        val (_, submitterTok) = employee(c, canReview = true)
        val (helperId, helperTok) = employee(c, canReview = true)

        val sub = submitPhoto(submitterTok, task, seed = 1, helperIds = listOf(helperId))
        assertEquals(200, sub.statusCode(), sub.body())
        val submissionId = parse(sub.body())["id"] as String

        // the submitter cannot review their own work (even though they hold can_review)
        val own = review(submitterTok, submissionId, "ACCEPTED")
        assertEquals(403, own.statusCode(), own.body())
        assertEquals("CANNOT_REVIEW_OWN", parse(own.body())["code"])
        // a tagged helper cannot review either
        val byHelper = review(helperTok, submissionId, "ACCEPTED")
        assertEquals(403, byHelper.statusCode(), byHelper.body())
        assertEquals("CANNOT_REVIEW_OWN", parse(byHelper.body())["code"])

        // a plain employee (not lead, no flag) is forbidden outright
        val (_, plainTok) = employee(c)
        val plain = review(plainTok, submissionId, "ACCEPTED")
        assertEquals(403, plain.statusCode(), plain.body())
        assertEquals("FORBIDDEN", parse(plain.body())["code"])

        // an independent employee with the can_review flag may review
        val (_, flaggedTok) = employee(c, canReview = true)
        assertEquals(200, review(flaggedTok, submissionId, "ACCEPTED").statusCode())
    }

    @Test
    fun `a lead may review by virtue of being the effective lead`() {
        val c = setup()
        val it = item(c, "PHOTO")
        val task = seedTask(c, it)
        val (_, submitterTok) = employee(c)
        val (leadId, leadTok) = employee(c)
        setLead(c, leadId)

        val submissionId = parse(submitPhoto(submitterTok, task, seed = 1).body())["id"] as String
        assertEquals(200, review(leadTok, submissionId, "ACCEPTED").statusCode())
    }

    // --- FOUNDER override --------------------------------------------------

    @Test
    fun `a non-founder cannot change a decided submission but the FOUNDER can override`() {
        val c = setup()
        val it = item(c, "PHOTO")
        val task = seedTask(c, it)
        val (_, submitterTok) = employee(c)
        val (leadId, leadTok) = employee(c)
        setLead(c, leadId)

        val submissionId = parse(submitPhoto(submitterTok, task, seed = 1).body())["id"] as String

        // the lead accepts
        assertEquals(200, review(leadTok, submissionId, "ACCEPTED").statusCode())
        assertEquals(TaskStatus.DONE, taskInstances.findById(task).get().status)

        // another flag-holder (not a founder) cannot change the decided submission
        val (_, otherReviewerTok) = employee(c, canReview = true)
        val blocked = review(otherReviewerTok, submissionId, "REJECTED")
        assertEquals(409, blocked.statusCode(), blocked.body())
        assertEquals("ALREADY_REVIEWED", parse(blocked.body())["code"])

        // the FOUNDER overrides: reject -> task reopens
        val override = review(c.token, submissionId, "REJECTED")
        assertEquals(200, override.statusCode(), override.body())
        assertEquals(true, parse(override.body())["override"])
        assertEquals("PENDING", parse(override.body())["taskStatus"])
    }

    // --- zone progress N of M ---------------------------------------------

    @Test
    fun `zone progress counts DONE over total for a period`() {
        val c = setup()
        val z1 = item(c, "PHOTO"); val z2 = item(c, "PHOTO"); val z3 = item(c, "PHOTO")
        // 3 zones in the same period W:2026-W01 (distinct items -> allowed by the (schedule,item,period) unique)
        val t1 = seedTask(c, z1, periodKey = "W:2026-W01")
        seedTask(c, z2, periodKey = "W:2026-W01")
        seedTask(c, z3, periodKey = "W:2026-W01")
        val (_, empTok) = employee(c)

        // before anything is done: 0 of 3
        val before = send("GET", "/api/v1/schedules/${c.schedule}/progress?periodKey=W:2026-W01", null, bearer(c.token))
        assertEquals(200, before.statusCode(), before.body())
        assertEquals(3L, (parse(before.body())["total"] as Number).toLong())
        assertEquals(0L, (parse(before.body())["done"] as Number).toLong())

        // accept zone 1 -> 1 of 3
        val submissionId = parse(submitPhoto(empTok, t1, seed = 1).body())["id"] as String
        assertEquals(200, review(c.token, submissionId, "ACCEPTED").statusCode())
        val after = send("GET", "/api/v1/schedules/${c.schedule}/progress?periodKey=W:2026-W01", null, bearer(c.token))
        assertEquals(1L, (parse(after.body())["done"] as Number).toLong())
        assertEquals(3L, (parse(after.body())["total"] as Number).toLong())
    }

    // --- MANUAL scoring ---------------------------------------------------

    @Test
    fun `manual score maps FULL PARTIAL ZERO to points times 1, half, 0 and needs can_score`() {
        val c = setup()
        val manual = item(c, "MANUAL", points = 20)

        fun score(token: String, grade: String): HttpResponse<String> =
            post("/api/v1/task-instances/${seedTask(c, manual, photoRequired = false)}/manual-score", mapOf("grade" to grade), bearer(token))

        // a plain employee cannot score
        val (_, plainTok) = employee(c)
        assertEquals(403, score(plainTok, "FULL").statusCode())

        // a can_score holder scores; FULL=20, PARTIAL=10, ZERO=0
        val (_, scorerTok) = employee(c, canScore = true)
        assertEquals(20, (parse(score(scorerTok, "FULL").body())["awardedPoints"] as Number).toInt())
        assertEquals(10, (parse(score(scorerTok, "PARTIAL").body())["awardedPoints"] as Number).toInt())
        assertEquals(0, (parse(score(scorerTok, "ZERO").body())["awardedPoints"] as Number).toInt())
    }

    @Test
    fun `a scorer cannot score a task they submitted`() {
        val c = setup()
        // a MANUAL task (no photo) that the scorer themselves answered: self-scoring must be refused
        val manual = item(c, "MANUAL", points = 20)
        val task = seedTask(c, manual, photoRequired = false)
        val (_, bothTok) = employee(c, canScore = true)
        assertEquals(200, submitNoPhoto(bothTok, task).statusCode())

        val res = post("/api/v1/task-instances/$task/manual-score", mapOf("grade" to "FULL"), bearer(bothTok))
        assertEquals(403, res.statusCode(), res.body())
        assertEquals("CANNOT_SCORE_OWN", parse(res.body())["code"])
    }

    // --- granting can_score via the employees endpoint --------------------

    @Test
    fun `a founder grants can_score through the employees endpoint, a non-founder cannot`() {
        val c = setup()
        val (empId, empTok) = employee(c) // no flags yet

        // a non-founder cannot grant
        val byEmp = send("PUT", "/api/v1/employees/$empId/permissions", mapOf("canScore" to true, "canReview" to false), bearer(empTok))
        assertEquals(403, byEmp.statusCode(), byEmp.body())

        // the founder grants can_score
        val grant = send("PUT", "/api/v1/employees/$empId/permissions", mapOf("canScore" to true, "canReview" to false), bearer(c.token))
        assertEquals(200, grant.statusCode(), grant.body())
        assertEquals(true, parse(grant.body())["canScore"])

        // the same token now works (flags are read from the DB per request, no re-issue needed)
        val manual = item(c, "MANUAL", points = 10)
        val scored = post("/api/v1/task-instances/${seedTask(c, manual, photoRequired = false)}/manual-score", mapOf("grade" to "FULL"), bearer(empTok))
        assertEquals(200, scored.statusCode(), scored.body())
        assertEquals(10, (parse(scored.body())["awardedPoints"] as Number).toInt())
    }

    // --- NUMERIC scoring: bands [lower, upper), 0 in the best band ---------

    @Test
    fun `numeric bands are half-open and zero falls into the best band for a lower-is-better metric`() {
        val c = setup()
        val numeric = item(c, "NUMERIC")

        // lower-is-better (e.g. write-offs): [0,3) is best (10 pts), [3,6) mid (5), [6, +inf) worst (0)
        fun band(lower: Any?, upper: Any?, points: Int, label: String) {
            val res = post(
                "/api/v1/items/$numeric/numeric-bands",
                mapOf("lowerBound" to lower, "upperBound" to upper, "points" to points, "label" to label, "sortOrder" to points),
                bearer(c.token),
            )
            assertEquals(201, res.statusCode(), res.body())
        }
        band(0, 3, 10, "best")
        band(3, 6, 5, "mid")
        band(6, null, 0, "worst")

        val (_, scorerTok) = employee(c, canScore = true)
        fun scoreValue(v: Any): Int {
            val res = post("/api/v1/task-instances/${seedTask(c, numeric, photoRequired = false)}/numeric-score", mapOf("value" to v), bearer(scorerTok))
            assertEquals(200, res.statusCode(), res.body())
            return (parse(res.body())["awardedPoints"] as Number).toInt()
        }

        assertEquals(10, scoreValue(0), "zero is the best band for a lower-is-better metric")
        assertEquals(10, scoreValue(2), "2 is in [0,3)")
        assertEquals(5, scoreValue(3), "the lower bound is inclusive: 3 is in [3,6)")
        assertEquals(0, scoreValue(6), "the upper bound is exclusive: 6 falls into [6, +inf)")
        assertEquals(0, scoreValue(99))
    }

    @Test
    fun `a numeric value with no matching band is a 400`() {
        val c = setup()
        val numeric = item(c, "NUMERIC")
        assertEquals(
            201,
            post("/api/v1/items/$numeric/numeric-bands", mapOf("lowerBound" to 0, "upperBound" to 10, "points" to 5), bearer(c.token)).statusCode(),
        )
        val (_, scorerTok) = employee(c, canScore = true)
        val res = post("/api/v1/task-instances/${seedTask(c, numeric, photoRequired = false)}/numeric-score", mapOf("value" to 50), bearer(scorerTok))
        assertEquals(400, res.statusCode(), res.body())
        assertEquals("NOT_SCORABLE", parse(res.body())["code"])
    }
}
