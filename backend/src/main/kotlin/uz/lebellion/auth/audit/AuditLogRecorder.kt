package uz.lebellion.auth.audit

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Appends rows to audit_log. Uses a native INSERT with explicit `CAST(? AS jsonb)` / `CAST(? AS uuid)`
 * (not a JPA entity) to avoid Hibernate's JSON mapper and keep null handling simple. The table is
 * intentionally generic so future events (e.g. PASSWORD_RESET_BY_FOUNDER) fit without a migration.
 */
@Component
class AuditLogRecorder(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun record(
        organizationId: UUID,
        eventType: String,
        actorUserId: UUID? = null,
        targetType: String? = null,
        targetId: UUID? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        val json = metadata?.let { objectMapper.writeValueAsString(it) }
        jdbc.update(
            "INSERT INTO audit_log (organization_id, actor_user_id, event_type, target_type, target_id, metadata) " +
                "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS uuid), CAST(? AS jsonb))",
            organizationId.toString(),
            actorUserId?.toString(),
            eventType,
            targetType,
            targetId?.toString(),
            json,
        )
    }
}
