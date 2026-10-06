package uz.lebellion.schedule.web

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.schedule.service.ScheduleService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/templates/{templateId}/schedules")
class ScheduleController(private val scheduleService: ScheduleService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable templateId: UUID,
    ): List<ScheduleResponse> = scheduleService.list(principal, templateId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable templateId: UUID,
        @Valid @RequestBody req: CreateScheduleRequest,
    ): ScheduleResponse = scheduleService.create(principal, templateId, req)
}
