package uz.lebellion.schedule.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.org.service.UnitLeadService
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.notify.NotificationOutboxWriter
import uz.lebellion.schedule.repo.TaskInstanceRepository
import java.time.Clock
import java.time.Instant

/**
 * Marks overdue PENDING tasks as MISSED (server time is authoritative — client clocks are never trusted)
 * and flags the Unit + its effective lead via a notification_outbox row. Idempotent: an already-MISSED
 * task is not re-swept.
 */
@Service
class MissedSweeper(
    private val taskInstances: TaskInstanceRepository,
    private val unitLead: UnitLeadService,
    private val outbox: NotificationOutboxWriter,
    private val audit: AuditLogRecorder,
    private val clock: Clock,
) {

    /** Returns the number of tasks marked MISSED. */
    @Transactional
    fun sweep(): Int {
        val now = Instant.now(clock)
        val overdue = taskInstances.findByStatusAndDueAtLessThanEqual(TaskStatus.PENDING, now)
        for (task in overdue) {
            task.status = TaskStatus.MISSED
            taskInstances.save(task)
            val leadUserId = unitLead.effectiveLeadUserId(task.organizationId, task.unitId)
            audit.record(
                organizationId = task.organizationId,
                eventType = "TASK_MISSED",
                targetType = "TASK_INSTANCE",
                targetId = task.id,
                metadata = mapOf("unitId" to task.unitId.toString(), "leadUserId" to leadUserId?.toString()),
            )
            outbox.write(
                task.organizationId,
                "TASK_MISSED",
                mapOf(
                    "taskInstanceId" to task.id.toString(),
                    "unitId" to task.unitId.toString(),
                    "leadUserId" to leadUserId?.toString(),
                    "dueAt" to task.dueAt.toString(),
                ),
            )
        }
        return overdue.size
    }
}
