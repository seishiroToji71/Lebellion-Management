package uz.lebellion.kpi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.support.TestJwt
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * Seeds the KPI catalog from a small fixture (src/test/resources/kpi) via the configured paths: asserts
 * the sheet/criteria/bands land, totals validate (sum=100), the run is idempotent, and it is FOUNDER-only.
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
class KpiSeedIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")

        private fun resourcePath(p: String): String = File(KpiSeedIT::class.java.getResource(p)!!.toURI()).absolutePath

        @JvmStatic
        @DynamicPropertySource
        fun kpiPaths(registry: DynamicPropertyRegistry) {
            registry.add("lebellion.kpi.catalog-path") { resourcePath("/kpi/catalog.json") }
            registry.add("lebellion.kpi.bands-path") { resourcePath("/kpi/bands.json") }
        }
    }

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var appEncoder: JwtEncoder
    @Autowired lateinit var users: AppUserRepository

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun send(method: String, path: String, headers: Map<String, String>): HttpResponse<String> {
        val b = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun postJson(path: String, body: Map<String, Any?>, headers: Map<String, String>): HttpResponse<String> {
        val b = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun bearer(token: String) = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun array(body: String): List<Map<String, Any?>> = mapper.readValue(body, List::class.java) as List<Map<String, Any?>>

    private fun registerFounder(): Pair<String, String> {
        val reg = postJson(
            "/api/v1/auth/register",
            mapOf(
                "organizationName" to "Org-${UUID.randomUUID()}", "fullName" to "F",
                "email" to "f-${UUID.randomUUID()}@e.com", "password" to "sup3rsecret!",
            ),
            mapOf("X-App-Version" to "1.4.0", "X-Device-Id" to "d1"),
        )
        assertEquals(201, reg.statusCode(), reg.body())
        val body = parse(reg.body())
        @Suppress("UNCHECKED_CAST")
        val user = body["user"] as Map<String, Any?>
        return (body["accessToken"] as String) to (user["organizationId"] as String)
    }

    @Test
    fun `seed loads the sheet, validates totals, is idempotent, and is founder-only`() {
        val (token, orgId) = registerFounder()

        val first = send("POST", "/api/v1/kpi/seed", bearer(token))
        assertEquals(200, first.statusCode(), first.body())
        assertEquals(2, (parse(first.body())["criteria"] as Number).toInt())
        assertEquals(1, (parse(first.body())["sheets"] as Number).toInt())

        // the sheet is readable: one sheet, 100 points, 2 items, 4 bands
        val list = send("GET", "/api/v1/score-sheets", bearer(token))
        assertEquals(1, array(list.body()).size)
        val sheetId = array(list.body())[0]["id"] as String
        val sheet = parse(send("GET", "/api/v1/score-sheets/$sheetId", bearer(token)).body())
        assertEquals("BOSH_OSHPAZ", sheet["roleKey"])
        assertEquals("Bosh oshpaz", sheet["nameUz"])
        assertEquals(100, (sheet["totalPoints"] as Number).toInt())
        @Suppress("UNCHECKED_CAST")
        val items = sheet["items"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        assertEquals("C001", items[0]["criterionCode"])
        assertEquals("Крит один", items[0]["titleRu"], "short title from the catalog")
        @Suppress("UNCHECKED_CAST")
        val bandsView = sheet["bands"] as List<Map<String, Any?>>
        assertEquals(4, bandsView.size)

        // idempotent: re-seeding keeps exactly one sheet (upsert by role_key, children rebuilt)
        assertEquals(200, send("POST", "/api/v1/kpi/seed", bearer(token)).statusCode())
        assertEquals(1, array(send("GET", "/api/v1/score-sheets", bearer(token)).body()).size)
        val reSheet = parse(send("GET", "/api/v1/score-sheets/$sheetId", bearer(token)).body())
        assertEquals(2, (reSheet["items"] as List<*>).size)

        // a non-founder cannot seed (EMPLOYEE in a real unit — valid per the role-shape CHECK)
        val branch = parse(postJson("/api/v1/branches", mapOf("name" to "B"), bearer(token)).body())["id"] as String
        val unit = parse(postJson("/api/v1/branches/$branch/units", mapOf("name" to "U"), bearer(token)).body())["id"] as String
        val emp = users.save(AppUser(organizationId = UUID.fromString(orgId), name = "E", role = Role.EMPLOYEE, unitId = UUID.fromString(unit)))
        val empTok = TestJwt.mint(appEncoder, subject = emp.id!!.toString(), orgId = orgId, role = "EMPLOYEE", tokenVersion = 0)
        assertEquals(403, send("POST", "/api/v1/kpi/seed", bearer(empTok)).statusCode())
    }
}
