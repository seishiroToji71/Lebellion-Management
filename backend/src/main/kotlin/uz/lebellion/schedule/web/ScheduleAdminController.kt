package uz.lebellion.schedule.web

import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.schedule.service.ScheduleAdminService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/schedules/{scheduleId}")
class ScheduleAdminController(private val scheduleAdminService: ScheduleAdminService) {

    /** Edit a schedule; cancels future PENDING and (if still active) regenerates from the new definition. */
    @PutMapping
    fun update(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable scheduleId: UUID,
        @Valid @RequestBody req: UpdateScheduleRequest,
    ): ScheduleResponse = scheduleAdminService.update(principal, scheduleId, req)

    /** Deactivate a schedule; cancels its future PENDING instances (no regeneration). */
    @PostMapping("/deactivate")
    fun deactivate(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable scheduleId: UUID,
    ): ScheduleResponse = scheduleAdminService.deactivate(principal, scheduleId)
}
