package uz.lebellion.schedule.web

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.schedule.service.TaskInstanceService
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/units/{unitId}/task-instances")
class TaskInstanceController(private val taskInstanceService: TaskInstanceService) {

    /** Upcoming task instances for a unit. `from`/`to` are optional ISO-8601 instants (default: next 7 days). */
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable unitId: UUID,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): List<TaskInstanceResponse> =
        taskInstanceService.list(principal, unitId, from?.let(::parseInstant), to?.let(::parseInstant))

    private fun parseInstant(value: String): Instant =
        try {
            Instant.parse(value)
        } catch (_: Exception) {
            throw RequestValidationException("invalid instant: $value")
        }
}
