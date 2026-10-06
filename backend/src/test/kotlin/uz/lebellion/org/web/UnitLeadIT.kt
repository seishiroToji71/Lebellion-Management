package uz.lebellion.org.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * Unit-level lead / acting_lead (P2-2): set/replace, precedence (acting_lead -> lead -> branch manager),
 * a deactivated lead falling through, role/branch scoping, tenant isolation, and assignment validation.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "lebellion.auth.registration-enabled=true",
        "lebellion.auth.rate-limit.register-ip.limit=100000",
    ],
)
@Testcontainers
class UnitLeadIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var appEncoder: JwtEncoder
    @Autowired lateinit var users: AppUserRepository

    private val http: HttpClient = HttpClient.newHttpClient()

    // --- HTTP helpers ----------------------------------------------------

    private fun send(method: String, path: String, body: Map<String, Any?>?, headers: Map<String, String>): HttpResponse<String> {
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
        val builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path")).method(method, publisher)
        if (body != null) builder.header("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: Map<String, Any?>, headers: Map<String, String>) = send("POST", path, body, headers)
    private fun put(path: String, body: Map<String, Any?>, headers: Map<String, String>) = send("PUT", path, body, headers)
    private fun get(path: String, headers: Map<String, String>) = send("GET", path, null, headers)

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    // --- principals & seeding --------------------------------------------

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

    private fun seedManager(orgId: String, branchId: String): AppUser = users.save(
        AppUser(
            organizationId = UUID.fromString(orgId),
            name = "Branch Manager",
            role = Role.BRANCH_MANAGER,
            email = "mgr-${UUID.randomUUID()}@example.com",
            branchId = UUID.fromString(branchId),
            passwordHash = "seeded-not-used",
        ),
    )

    private fun seedEmployee(orgId: String, unitId: String): AppUser = users.save(
        AppUser(
            organizationId = UUID.fromString(orgId),
            name = "Emp-${UUID.randomUUID()}",
            role = Role.EMPLOYEE,
            unitId = UUID.fromString(unitId),
        ),
    )

    private fun managerToken(orgId: String, branchId: String): String {
        val m = seedManager(orgId, branchId)
        return TestJwt.mint(appEncoder, subject = m.id!!.toString(), orgId = orgId, role = "BRANCH_MANAGER", tokenVersion = 0)
    }

    private fun employeeToken(orgId: String, unitId: String): String {
        val e = seedEmployee(orgId, unitId)
        return TestJwt.mint(appEncoder, subject = e.id!!.toString(), orgId = orgId, role = "EMPLOYEE", tokenVersion = 0)
    }

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

    private fun setLead(token: String, unitId: String, body: Map<String, Any?>) =
        put("/api/v1/units/$unitId/lead", body, bearer(token))

    private fun getLead(token: String, unitId: String) = get("/api/v1/units/$unitId/lead", bearer(token))

    // --- precedence -------------------------------------------------------

    @Test
    fun `lead resolves by precedence acting over lead, and clearing drops to none`() {
        val founder = registerFounder()
        val unit = unitId(founder.token, branchId(founder.token))
        val chef = seedEmployee(founder.orgId, unit).id!!.toString()
        val stand = seedEmployee(founder.orgId, unit).id!!.toString()

        val withLead = setLead(founder.token, unit, mapOf("leadEmployeeId" to chef))
        assertEquals(200, withLead.statusCode(), withLead.body())
        parse(withLead.body()).let {
            assertEquals(chef, it["leadEmployeeId"])
            assertEquals(chef, it["effectiveLeadUserId"])
            assertEquals("LEAD", it["source"])
        }

        parse(setLead(founder.token, unit, mapOf("leadEmployeeId" to chef, "actingLeadEmployeeId" to stand)).body()).let {
            assertEquals(stand, it["effectiveLeadUserId"])
            assertEquals("ACTING_LEAD", it["source"])
        }

        // clearing both (no branch manager in this unit's branch) -> NONE
        parse(setLead(founder.token, unit, emptyMap()).body()).let {
            assertNull(it["leadEmployeeId"])
            assertNull(it["actingLeadEmployeeId"])
            assertNull(it["effectiveLeadUserId"])
            assertEquals("NONE", it["source"])
        }
    }

    // --- fallback & deactivation -----------------------------------------

    @Test
    fun `with no lead the branch manager is the fallback, and a deactivated lead falls through`() {
        val founder = registerFounder()
        val branch = branchId(founder.token)
        val unit = unitId(founder.token, branch)
        val manager = seedManager(founder.orgId, branch).id!!.toString()

        // no lead assigned -> fallback to the branch manager
        parse(getLead(founder.token, unit).body()).let {
            assertEquals("BRANCH_MANAGER", it["source"])
            assertEquals(manager, it["effectiveLeadUserId"])
        }

        // assign a lead, then deactivate them -> resolution falls through to the manager again
        val chef = seedEmployee(founder.orgId, unit)
        assertEquals(200, setLead(founder.token, unit, mapOf("leadEmployeeId" to chef.id!!.toString())).statusCode())
        chef.isActive = false
        users.save(chef)
        parse(getLead(founder.token, unit).body()).let {
            assertEquals(chef.id!!.toString(), it["leadEmployeeId"], "assignment is retained")
            assertEquals(manager, it["effectiveLeadUserId"], "but a deactivated lead does not resolve")
            assertEquals("BRANCH_MANAGER", it["source"])
        }
    }

    // --- scoping ----------------------------------------------------------

    @Test
    fun `scoping confines managers to their branch, refuses employees, and hides other tenants`() {
        val founder = registerFounder()
        val a = branchId(founder.token)
        val b = branchId(founder.token)
        val unitA = unitId(founder.token, a)
        val unitB = unitId(founder.token, b)
        val chefA = seedEmployee(founder.orgId, unitA).id!!.toString()

        // a manager of A may set A's lead, but B's is a 404 (not in scope)
        val mgrA = managerToken(founder.orgId, a)
        assertEquals(200, setLead(mgrA, unitA, mapOf("leadEmployeeId" to chefA)).statusCode())
        assertEquals(404, getLead(mgrA, unitB).statusCode())
        assertEquals(404, setLead(mgrA, unitB, emptyMap()).statusCode())

        // an employee may neither read nor set
        val emp = employeeToken(founder.orgId, unitA)
        assertEquals(403, getLead(emp, unitA).statusCode())
        assertEquals(403, setLead(emp, unitA, emptyMap()).statusCode())

        // another org cannot see this unit
        val org2 = registerFounder()
        assertEquals(404, getLead(org2.token, unitA).statusCode())
    }

    // --- assignment validation -------------------------------------------

    @Test
    fun `assigning a non-member or inactive user is rejected`() {
        val founder = registerFounder()
        val unitA = unitId(founder.token, branchId(founder.token))
        val unitB = unitId(founder.token, branchId(founder.token))

        // a member of a different unit cannot be unit A's lead
        val memberOfB = seedEmployee(founder.orgId, unitB).id!!.toString()
        assertEquals(400, setLead(founder.token, unitA, mapOf("leadEmployeeId" to memberOfB)).statusCode())

        // an inactive member of unit A cannot be the lead
        val inactive = seedEmployee(founder.orgId, unitA).also { it.isActive = false; users.save(it) }
        assertEquals(400, setLead(founder.token, unitA, mapOf("leadEmployeeId" to inactive.id!!.toString())).statusCode())

        // a user of another org cannot be assigned
        val other = registerFounder()
        val foreign = seedEmployee(other.orgId, unitId(other.token, branchId(other.token))).id!!.toString()
        assertEquals(400, setLead(founder.token, unitA, mapOf("leadEmployeeId" to foreign)).statusCode())
    }
}
