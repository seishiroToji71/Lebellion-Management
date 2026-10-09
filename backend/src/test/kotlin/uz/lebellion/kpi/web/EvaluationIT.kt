package uz.lebellion.kpi.web

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
import uz.lebellion.checklist.domain.ItemType
import uz.lebellion.kpi.domain.BandLabel
import uz.lebellion.kpi.domain.KpiCriterion
import uz.lebellion.kpi.domain.ScoreBand
import uz.lebellion.kpi.domain.ScoreSheet
import uz.lebellion.kpi.domain.ScoreSheetItem
import uz.lebellion.kpi.repo.KpiCriterionRepository
import uz.lebellion.kpi.repo.ScoreBandRepository
import uz.lebellion.kpi.repo.ScoreSheetItemRepository
import uz.lebellion.kpi.repo.ScoreSheetRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * KPI evaluation (P2-6b, part 2): read a seeded score sheet, open a monthly evaluation, set awards
 * (∈ {0, points/2, points}), finalize with the half-up band lookup (final 90.5 -> 91 -> BASE), and the
 * guards: can_score, no self-evaluation, FINALIZED is immutable, invalid award rejected.
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
class EvaluationIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var appEncoder: JwtEncoder
    @Autowired lateinit var users: AppUserRepository
    @Autowired lateinit var criteria: KpiCriterionRepository
    @Autowired lateinit var sheets: ScoreSheetRepository
    @Autowired lateinit var sheetItems: ScoreSheetItemRepository
    @Autowired lateinit var bands: ScoreBandRepository

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun send(method: String, path: String, body: Map<String, Any?>?, headers: Map<String, String>): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
        val b = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path")).method(method, publisher)
        if (body != null) b.header("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: Map<String, Any?>?, headers: Map<String, String>) = send("POST", path, body, headers)
    private fun get(path: String, headers: Map<String, String>) = send("GET", path, null, headers)
    private fun bearer(token: String) = mapOf("X-App-Version" to "1.4.0", "Authorization" to "Bearer $token")

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    private data class Founder(val token: String, val orgId: String, val userId: String)

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
        return Founder(body["accessToken"] as String, user["organizationId"] as String, user["id"] as String)
    }

    /** A unit for the org (EMPLOYEEs must carry a unit_id per the V2 role-shape CHECK). */
    private fun createUnit(token: String): String {
        val branch = parse(post("/api/v1/branches", mapOf("name" to "B"), bearer(token)).body())["id"] as String
        return parse(post("/api/v1/branches/$branch/units", mapOf("name" to "U"), bearer(token)).body())["id"] as String
    }

    private fun employee(orgId: String, unitId: String, canScore: Boolean = false): Pair<String, String> {
        val e = users.save(AppUser(organizationId = UUID.fromString(orgId), name = "E-${UUID.randomUUID()}", role = Role.EMPLOYEE, unitId = UUID.fromString(unitId), canScore = canScore))
        val id = e.id!!.toString()
        return id to TestJwt.mint(appEncoder, subject = id, orgId = orgId, role = "EMPLOYEE", tokenVersion = 0)
    }

    /** Seed a chef sheet (items 81 + 19 = 100) with the chef bands. Returns (sheetId, item81Id, item19Id). */
    private fun seedChefSheet(orgId: String): Triple<String, String, String> {
        val org = UUID.fromString(orgId)
        val c1 = criteria.save(KpiCriterion(org, "C001", ItemType.MANUAL, "Крит 1", "Krit 1")).id!!
        val c2 = criteria.save(KpiCriterion(org, "C002", ItemType.MANUAL, "Крит 2", "Krit 2")).id!!
        val sheet = sheets.save(ScoreSheet(org, "BOSH_OSHPAZ", "Шеф повар", "Bosh oshpaz")).id!!
        val i81 = sheetItems.save(ScoreSheetItem(org, sheet, c1, points = 81, sortOrder = 0)).id!!
        val i19 = sheetItems.save(ScoreSheetItem(org, sheet, c2, points = 19, sortOrder = 1)).id!!
        listOf(
            ScoreBand(org, sheet, 96, 100, BandLabel.BONUS, 20, 0),
            ScoreBand(org, sheet, 91, 95, BandLabel.BASE, 0, 1),
            ScoreBand(org, sheet, 86, 90, BandLabel.PENALTY, -10, 2),
            ScoreBand(org, sheet, 0, 85, BandLabel.PENALTY, -20, 3),
        ).forEach { bands.save(it) }
        return Triple(sheet.toString(), i81.toString(), i19.toString())
    }

    @Test
    fun `read a seeded sheet, then evaluate with half-up band lookup (90_5 to 91, BASE)`() {
        val founder = registerFounder()
        val (sheetId, i81, i19) = seedChefSheet(founder.orgId)
        val (employeeId, _) = employee(founder.orgId, createUnit(founder.token))

        // the sheet reads back with 100 points, 2 items, 4 bands
        val sheetView = get("/api/v1/score-sheets/$sheetId", bearer(founder.token))
        assertEquals(200, sheetView.statusCode(), sheetView.body())
        assertEquals(100, (parse(sheetView.body())["totalPoints"] as Number).toInt())

        // open the evaluation
        val created = post(
            "/api/v1/evaluations",
            mapOf("employeeId" to employeeId, "scoreSheetId" to sheetId, "periodMonth" to "2026-10-15"),
            bearer(founder.token),
        )
        assertEquals(201, created.statusCode(), created.body())
        val evalId = parse(created.body())["id"] as String
        assertEquals("2026-10-01", parse(created.body())["periodMonth"], "normalised to the 1st")

        // award item81 = FULL (81), item19 = PARTIAL (9.5) -> final 90.5
        assertEquals(200, post("/api/v1/evaluations/$evalId/awards", mapOf("scoreSheetItemId" to i81, "awardedPoints" to 81), bearer(founder.token)).statusCode())
        val drafted = post("/api/v1/evaluations/$evalId/awards", mapOf("scoreSheetItemId" to i19, "awardedPoints" to 9.5), bearer(founder.token))
        assertEquals(200, drafted.statusCode(), drafted.body())
        assertEquals(90.5, (parse(drafted.body())["finalScore"] as Number).toDouble(), "live draft sum")

        // an award outside {0, points/2, points} is rejected
        val bad = post("/api/v1/evaluations/$evalId/awards", mapOf("scoreSheetItemId" to i81, "awardedPoints" to 50), bearer(founder.token))
        assertEquals(400, bad.statusCode(), bad.body())
        assertEquals("INVALID_AWARD", parse(bad.body())["code"])

        // finalize: 90.5 rounds half-up to 91 -> chef [91,95] BASE 0
        val finalized = post("/api/v1/evaluations/$evalId/finalize", null, bearer(founder.token))
        assertEquals(200, finalized.statusCode(), finalized.body())
        val fb = parse(finalized.body())
        assertEquals("FINALIZED", fb["status"])
        assertEquals(90.5, (fb["finalScore"] as Number).toDouble())
        @Suppress("UNCHECKED_CAST")
        val rec = fb["recommendation"] as Map<String, Any?>
        assertEquals("BASE", rec["label"], "90.5 -> 91 lands in the BASE band, not the 86-90 penalty")
        assertEquals(0, (rec["percent"] as Number).toInt())

        // FINALIZED is immutable
        val afterFinal = post("/api/v1/evaluations/$evalId/awards", mapOf("scoreSheetItemId" to i81, "awardedPoints" to 0), bearer(founder.token))
        assertEquals(409, afterFinal.statusCode(), afterFinal.body())
        assertEquals("EVALUATION_FINALIZED", parse(afterFinal.body())["code"])
    }

    @Test
    fun `can_score is required and a reviewer cannot evaluate themselves`() {
        val founder = registerFounder()
        val (sheetId, _, _) = seedChefSheet(founder.orgId)
        val unit = createUnit(founder.token)
        val (employeeId, _) = employee(founder.orgId, unit)

        // a plain employee (no can_score) cannot open an evaluation
        val (_, plainTok) = employee(founder.orgId, unit, canScore = false)
        val forbidden = post("/api/v1/evaluations", mapOf("employeeId" to employeeId, "scoreSheetId" to sheetId, "periodMonth" to "2026-10-01"), bearer(plainTok))
        assertEquals(403, forbidden.statusCode(), forbidden.body())

        // a FOUNDER may, but not on themselves
        val self = post("/api/v1/evaluations", mapOf("employeeId" to founder.userId, "scoreSheetId" to sheetId, "periodMonth" to "2026-10-01"), bearer(founder.token))
        assertEquals(403, self.statusCode(), self.body())
        assertEquals("CANNOT_SCORE_OWN", parse(self.body())["code"])

        // a can_score employee may evaluate someone else
        val (scorerId, scorerTok) = employee(founder.orgId, unit, canScore = true)
        assertTrue(scorerId != employeeId)
        assertEquals(201, post("/api/v1/evaluations", mapOf("employeeId" to employeeId, "scoreSheetId" to sheetId, "periodMonth" to "2026-10-01"), bearer(scorerTok)).statusCode())
    }
}
