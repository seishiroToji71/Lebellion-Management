package uz.lebellion.schedule.web

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.schedule.service.TaskSubmissionService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/task-instances/{taskInstanceId}")
class TaskSubmissionController(private val taskSubmissionService: TaskSubmissionService) {

    /**
     * Mark a task submitted (P2-4: status only; photos/dup-protection/helper tags come in P2-5).
     * A CANCELLED task yields 409 TASK_CANCELLED; any other non-PENDING yields 409 TASK_NOT_OPEN.
     */
    @PostMapping("/submit")
    fun submit(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable taskInstanceId: UUID,
    ): TaskInstanceResponse = taskSubmissionService.submit(principal, taskInstanceId)
}
