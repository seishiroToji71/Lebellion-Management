package uz.lebellion.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

@SpringBootTest
@Testcontainers
class FlywaySchemaHistoryIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun `all migrations are recorded as success in contiguous order`() {
        val rows = mutableListOf<Triple<String?, String?, Boolean>>()
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank",
                ).use { rs ->
                    while (rs.next()) {
                        rows += Triple(rs.getString("version"), rs.getString("description"), rs.getBoolean("success"))
                    }
                }
            }
        }

        println("---- flyway_schema_history ----")
        rows.forEach { (v, d, s) -> println("version=$v | description=$d | success=$s") }
        println("---- end ----")

        assertTrue(rows.isNotEmpty(), "expected at least one migration")
        assertEquals((1..rows.size).map { it.toString() }, rows.map { it.first }, "versions must be contiguous 1..N")
        assertTrue(rows.all { it.third }, "all migrations must be success=true")
    }
}
