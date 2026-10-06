package uz.lebellion.schedule.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.schedule.domain.TaskInstance
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.notify.NotificationOutboxWriter
import uz.lebellion.schedule.repo.TaskInstanceRepository
import uz.lebellion.schedule.web.TaskCancelledException
import uz.lebellion.schedule.web.TaskInstanceResponse
import uz.lebellion.schedule.web.TaskNotOpenException
import uz.lebellion.schedule.web.toResponse
import java.util.UUID

/**
 * Minimal P2-4 submission: transitions a PENDING task to SUBMITTED (photos / dup-protection / helper
 * tags arrive in P2-5). A submission to a CANCELLED task is refused with a clear 409 and the attempt is
 * recorded (an offline client's late upload gets a meaningful error, never a 404).
 */
@Service
class TaskSubmissionService(
    private val taskInstances: TaskInstanceRepository,
    private val users: AppUserRepository,
    private val units: UnitRepository,
    private val audit: AuditLogRecorder,
    private val outbox: NotificationOutboxWriter,
    private val attemptAuditor: TaskAttemptAuditor,
) {

    @Transactional
    fun submit(principal: AuthPrincipal, taskInstanceId: UUID): TaskInstanceResponse {
        val task = taskInstances.findByIdAndOrganizationId(taskInstanceId, principal.organizationId)
            ?: throw NotFoundException("task not found")
        requireInScope(principal, task)

        when (task.status) {
            TaskStatus.CANCELLED -> {
                // preserve the attempt (own transaction, survives this rollback), then reject — not a 404
                attemptAuditor.recordRejected(task.organizationId, principal.userId, task.id!!, "CANCELLED")
                throw TaskCancelledException()
            }
            TaskStatus.PENDING -> {
                task.status = TaskStatus.SUBMITTED
                taskInstances.save(task)
                audit.record(
                    organizationId = task.organizationId,
                    eventType = "TASK_SUBMITTED",
                    actorUserId = principal.userId,
                    targetType = "TASK_INSTANCE",
                    targetId = task.id,
                )
                outbox.write(
                    task.organizationId,
                    "TASK_SUBMITTED",
                    mapOf("taskInstanceId" to task.id.toString(), "unitId" to task.unitId.toString()),
                )
                return task.toResponse()
            }
            else -> {
                attemptAuditor.recordRejected(task.organizationId, principal.userId, task.id!!, task.status.name)
                throw TaskNotOpenException(task.status)
            }
        }
    }

    /** FOUNDER: any unit; BRANCH_MANAGER: own branch; EMPLOYEE: own unit. Out of scope reads as 404. */
    private fun requireInScope(principal: AuthPrincipal, task: TaskInstance) {
        when (principal.role) {
            Role.FOUNDER -> return
            Role.BRANCH_MANAGER -> {
                val unit = units.findByIdAndOrganizationId(task.unitId, principal.organizationId)
                if (unit == null || unit.branchId != principal.branchId) throw NotFoundException("task not found")
            }
            Role.EMPLOYEE -> {
                val me = users.findByIdAndOrganizationId(principal.userId, principal.organizationId)
                if (me == null || me.unitId != task.unitId) throw NotFoundException("task not found")
            }
        }
    }
}
