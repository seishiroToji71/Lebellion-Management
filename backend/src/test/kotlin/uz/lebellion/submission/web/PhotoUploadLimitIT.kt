package uz.lebellion.submission.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
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

/** The per-photo size cap: with a tiny `max-photo-bytes`, a valid-but-larger image is a 413. */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
        "lebellion.schedule.generation-enabled=false",
        "lebellion.schedule.missed-sweep-enabled=false",
        "lebellion.submission.max-photo-bytes=256",
    ],
)
@Testcontainers
class PhotoUploadLimitIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var taskInstances: TaskInstanceRepository

    private val http: HttpClient = HttpClient.newHttpClient()

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>
    private fun bearer(token: String) = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")

    private fun post(path: String, body: Map<String, Any?>, headers: Map<String, String>): HttpResponse<String> {
        val b = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `an image over the configured size cap is 413`() {
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
        val item = parse(post("/api/v1/templates/$template/items", mapOf("type" to "PHOTO", "titleRu" to "r", "titleUz" to "u", "photoRequired" to true), bearer(token)).body())["id"] as String
        val schedule = parse(post("/api/v1/templates/$template/schedules", mapOf("recurrence" to "WEEKLY"), bearer(token)).body())["id"] as String

        val task = taskInstances.save(
            TaskInstance(
                organizationId = UUID.fromString(orgId),
                scheduleId = UUID.fromString(schedule),
                templateId = UUID.fromString(template),
                itemId = UUID.fromString(item),
                unitId = UUID.fromString(unit),
                periodKey = "s-${UUID.randomUUID()}",
                dueAt = Instant.now().plusSeconds(3600),
                photoRequired = true,
                status = TaskStatus.PENDING,
            ),
        ).id!!

        // a 64x64 gradient PNG is well over 256 bytes but well under the multipart transport cap
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) for (x in 0 until 64) img.setRGB(x, y, (x * 4 shl 16) or (y * 4 shl 8))
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out)

        val built = MultipartBody.build(
            listOf(MultipartBody.Field("answer", "true"), MultipartBody.FilePart("photo", "p.png", "image/png", out.toByteArray())),
        )
        val res = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/api/v1/task-instances/$task/submit"))
                .header("Content-Type", built.contentType)
                .header("X-App-Version", "1.4.0")
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofByteArray(built.body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(413, res.statusCode(), res.body())
        assertEquals("PHOTO_TOO_LARGE", parse(res.body())["code"])
    }
}
