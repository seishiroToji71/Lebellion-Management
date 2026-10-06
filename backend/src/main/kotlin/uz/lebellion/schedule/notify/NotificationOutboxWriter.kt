package uz.lebellion.schedule.notify

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Appends rows to notification_outbox (the outbox pattern — no dispatcher yet in P2-4). Uses a native
 * INSERT with `CAST(? AS jsonb)` to avoid Hibernate's JSON mapper, exactly like [uz.lebellion.auth.audit.AuditLogRecorder].
 */
@Component
class NotificationOutboxWriter(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun write(organizationId: UUID, type: String, payload: Map<String, Any?>? = null) {
        val json = payload?.let { objectMapper.writeValueAsString(it) }
        jdbc.update(
            "INSERT INTO notification_outbox (organization_id, type, payload) " +
                "VALUES (CAST(? AS uuid), ?, CAST(? AS jsonb))",
            organizationId.toString(),
            type,
            json,
        )
    }
}
