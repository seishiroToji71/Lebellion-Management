package uz.lebellion.submission.web

import org.junit.jupiter.api.Assertions.assertEquals
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
import uz.lebellion.schedule.domain.TaskInstance
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.repo.TaskInstanceRepository
import uz.lebellion.submission.domain.SubmissionStatus
import uz.lebellion.submission.repo.SubmissionRepository
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
 * Photo submission (P2-5): the mandatory duplicate matrix (same / recompressed / cropped / different),
 * static-scene flagging, exclusion of rejected submissions from near-dup (exact SHA still blocks), helper
 * tag confirmation rules, upload format validation, and signed-URL media serving.
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
class PhotoSubmissionIT {

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
    @Autowired lateinit var submissions: SubmissionRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    private val http: HttpClient = HttpClient.newHttpClient()

    // --- test images ------------------------------------------------------

    private fun image(f: (x: Int, y: Int) -> Int): BufferedImage {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) for (x in 0 until 64) {
            val v = f(x, y).coerceIn(0, 255); img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        return img
    }

    private fun encode(img: BufferedImage, fmt: String): ByteArray {
        val out = ByteArrayOutputStream(); ImageIO.write(img, fmt, out); return out.toByteArray()
    }

    // a "tent" peaking at x=32 (rises then falls) yields real dHash bits; the same tent in y is a far hash.
    // A monotonic ramp would be degenerate (all-zeros dHash in both orientations).
    private val horizontal = image { x, _ -> 255 - Math.abs(x - 32) * 8 }
    private val vertical = image { _, y -> 255 - Math.abs(y - 32) * 8 }
    private fun basePng() = encode(horizontal, "png")
    private fun recompressedJpg() = encode(horizontal, "jpg")
    private fun croppedPng(): ByteArray {
        val sub = horizontal.getSubimage(2, 2, 60, 60)
        val scaled = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics(); g.drawImage(sub, 0, 0, 64, 64, null); g.dispose()
        return encode(scaled, "png")
    }
    private fun differentPng() = encode(vertical, "png")

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

    private fun submit(
        token: String,
        taskId: UUID,
        bytes: ByteArray?,
        filename: String = "p.png",
        contentType: String = "image/png",
        helperIds: List<String> = emptyList(),
    ): HttpResponse<String> {
        val parts = mutableListOf<MultipartBody.Part>(MultipartBody.Field("answer", "true"))
        helperIds.forEach { parts += MultipartBody.Field("helperUserIds", it) }
        if (bytes != null) parts += MultipartBody.FilePart("photo", filename, contentType, bytes)
        val built = MultipartBody.build(parts)
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/task-instances/$taskId/submit"))
            .header("Content-Type", built.contentType)
            .header("X-App-Version", "1.4.0")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofByteArray(built.body))
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString())
    }

    // --- org / task setup -------------------------------------------------

    private data class Ctx(val orgId: String, val token: String, val unit: String, val template: String, val schedule: String)

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
        return Ctx(orgId, token, unit, template, schedule)
    }

    private fun item(c: Ctx, staticScene: Boolean): String = parse(
        post(
            "/api/v1/templates/${c.template}/items",
            mapOf("type" to "PHOTO", "titleRu" to "r", "titleUz" to "u", "staticScene" to staticScene, "photoRequired" to true),
            bearer(c.token),
        ).body(),
    )["id"] as String

    private fun seedTask(c: Ctx, itemId: String): UUID = taskInstances.save(
        TaskInstance(
            organizationId = UUID.fromString(c.orgId),
            scheduleId = UUID.fromString(c.schedule),
            templateId = UUID.fromString(c.template),
            itemId = UUID.fromString(itemId),
            unitId = UUID.fromString(c.unit),
            periodKey = "s-${UUID.randomUUID()}",
            dueAt = Instant.now().plusSeconds(3600),
            photoRequired = true,
            status = TaskStatus.PENDING,
        ),
    ).id!!

    private fun employeeToken(orgId: String, unitId: String): String {
        val e = users.save(AppUser(organizationId = UUID.fromString(orgId), name = "E", role = Role.EMPLOYEE, unitId = UUID.fromString(unitId)))
        return TestJwt.mint(appEncoder, subject = e.id!!.toString(), orgId = orgId, role = "EMPLOYEE", tokenVersion = 0)
    }

    private fun blockedCount(orgId: String, reason: String): Int = jdbc.queryForObject(
        "select count(*) from blocked_attempt where organization_id = CAST(? AS uuid) and reason = ?", Int::class.java, orgId, reason,
    )!!

    // --- the mandatory duplicate matrix (non-static item) -----------------

    @Test
    fun `duplicate matrix - same blocked by SHA, recompressed and cropped blocked as near-dup, different accepted`() {
        val c = setup()
        val item = item(c, staticScene = false)

        val base = submit(c.token, seedTask(c, item), basePng())
        assertEquals(200, base.statusCode(), base.body())

        // same file -> exact SHA-256 -> 409
        val same = submit(c.token, seedTask(c, item), basePng())
        assertEquals(409, same.statusCode(), same.body())
        assertEquals("DUPLICATE_PHOTO", parse(same.body())["code"])

        // recompressed (same scene, different bytes) -> near-duplicate -> 409
        assertEquals(409, submit(c.token, seedTask(c, item), recompressedJpg(), filename = "p.jpg", contentType = "image/jpeg").statusCode())

        // slightly cropped -> near-duplicate -> 409
        assertEquals(409, submit(c.token, seedTask(c, item), croppedPng()).statusCode())

        // a genuinely different photo -> accepted
        assertEquals(200, submit(c.token, seedTask(c, item), differentPng()).statusCode())

        assertTrue(blockedCount(c.orgId, "EXACT_SHA") >= 1, "the exact resend is recorded")
        assertTrue(blockedCount(c.orgId, "NEAR_DUPLICATE") >= 2, "recompressed + cropped are recorded")
    }

    // --- static scene: near-duplicate is flagged, not blocked -------------

    @Test
    fun `a static-scene near-duplicate is flagged for review, not blocked`() {
        val c = setup()
        val item = item(c, staticScene = true)

        assertEquals(200, submit(c.token, seedTask(c, item), basePng()).statusCode())

        val near = submit(c.token, seedTask(c, item), recompressedJpg(), filename = "p.jpg", contentType = "image/jpeg")
        assertEquals(200, near.statusCode(), near.body())
        assertEquals(true, parse(near.body())["flagged"], "static-scene near-duplicate is flagged, not rejected")
    }

    // --- rejected submissions excluded from near-dup; exact SHA still blocks

    @Test
    fun `a re-shot near-duplicate after a rejection is allowed, but the exact rejected file is still blocked`() {
        val c = setup()
        val item = item(c, staticScene = false)

        val base = submit(c.token, seedTask(c, item), basePng())
        assertEquals(200, base.statusCode(), base.body())
        @Suppress("UNCHECKED_CAST")
        val submissionId = UUID.fromString(parse(base.body())["id"] as String)

        // the reviewer rejects it
        val rejected = submissions.findById(submissionId).get().apply { status = SubmissionStatus.REJECTED }
        submissions.save(rejected)

        // re-shooting the same scene (near-duplicate, different bytes) is NOT blocked — the rejected one is excluded
        assertEquals(200, submit(c.token, seedTask(c, item), recompressedJpg(), filename = "p.jpg", contentType = "image/jpeg").statusCode())

        // but resending the EXACT rejected file is still a duplicate
        assertEquals(409, submit(c.token, seedTask(c, item), basePng()).statusCode())
    }

    // --- helper tags ------------------------------------------------------

    @Test
    fun `only the tagged same-unit employee may confirm participation`() {
        val c = setup()
        val item = item(c, staticScene = false)
        val helper = users.save(AppUser(organizationId = UUID.fromString(c.orgId), name = "H", role = Role.EMPLOYEE, unitId = UUID.fromString(c.unit)))
        val helperId = helper.id!!.toString()
        val helperTok = TestJwt.mint(appEncoder, subject = helperId, orgId = c.orgId, role = "EMPLOYEE", tokenVersion = 0)

        val res = submit(c.token, seedTask(c, item), basePng(), helperIds = listOf(helperId))
        assertEquals(200, res.statusCode(), res.body())
        val submissionId = parse(res.body())["id"] as String
        @Suppress("UNCHECKED_CAST")
        val helpers = parse(res.body())["helpers"] as List<Map<String, Any?>>
        assertEquals(1, helpers.size)
        assertNull(helpers[0]["confirmedAt"], "not confirmed until the taggee acts")

        // a non-tagged employee cannot confirm (404)
        val stranger = employeeToken(c.orgId, c.unit)
        assertEquals(404, send("POST", "/api/v1/submissions/$submissionId/confirm", null, bearer(stranger)).statusCode())

        // the tagged employee confirms
        val confirm = send("POST", "/api/v1/submissions/$submissionId/confirm", null, bearer(helperTok))
        assertEquals(200, confirm.statusCode(), confirm.body())
        assertNotNull(parse(confirm.body())["confirmedAt"])
    }

    @Test
    fun `tagging a helper from another unit is rejected`() {
        val c = setup()
        val item = item(c, staticScene = false)
        val otherUnit = parse(
            post("/api/v1/branches/${parse(post("/api/v1/branches", mapOf("name" to "B2"), bearer(c.token)).body())["id"]}/units", mapOf("name" to "U2"), bearer(c.token)).body(),
        )["id"] as String
        val outsider = users.save(AppUser(organizationId = UUID.fromString(c.orgId), name = "O", role = Role.EMPLOYEE, unitId = UUID.fromString(otherUnit)))

        val res = submit(c.token, seedTask(c, item), basePng(), helperIds = listOf(outsider.id!!.toString()))
        assertEquals(400, res.statusCode(), res.body())
    }

    // --- upload validation ------------------------------------------------

    @Test
    fun `a non-image upload is 415 and a missing required photo is 400`() {
        val c = setup()
        val item = item(c, staticScene = false)

        assertEquals(415, submit(c.token, seedTask(c, item), "not an image".toByteArray(), filename = "x.png").statusCode())
        assertEquals(400, submit(c.token, seedTask(c, item), null).statusCode())
    }

    // --- signed media URL -------------------------------------------------

    @Test
    fun `a submitted photo is served via its signed URL, and a tampered signature is rejected`() {
        val c = setup()
        val item = item(c, staticScene = false)
        val res = submit(c.token, seedTask(c, item), basePng())
        assertEquals(200, res.statusCode(), res.body())
        @Suppress("UNCHECKED_CAST")
        val photo = parse(res.body())["photo"] as Map<String, Any?>
        val url = photo["url"] as String

        // signed URL needs no auth and no X-App-Version (it lives outside /api/v1)
        val ok = http.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$url")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, ok.statusCode())
        assertTrue(ok.body().isNotEmpty())

        // tampering with the signature is a 403
        val tampered = url.replace(Regex("sig=[^&]+"), "sig=deadbeef")
        val bad = http.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$tampered")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(403, bad.statusCode())
    }
}
