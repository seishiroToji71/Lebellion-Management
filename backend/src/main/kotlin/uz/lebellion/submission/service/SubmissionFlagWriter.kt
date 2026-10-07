package uz.lebellion.submission.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Sets a submission's `auto_flags` (jsonb) via a native UPDATE + `CAST(? AS jsonb)` — the same approach
 * as AuditLogRecorder — so the column stays off the JPA entity and out of Hibernate's JSON mapper.
 */
@Component
class SubmissionFlagWriter(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun setFlags(submissionId: UUID, flags: Map<String, Any?>) {
        jdbc.update(
            "UPDATE submission SET auto_flags = CAST(? AS jsonb) WHERE id = CAST(? AS uuid)",
            objectMapper.writeValueAsString(flags),
            submissionId.toString(),
        )
    }
}
