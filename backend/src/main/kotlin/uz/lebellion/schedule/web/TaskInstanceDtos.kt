package uz.lebellion.schedule.web

import uz.lebellion.schedule.domain.TaskInstance
import uz.lebellion.schedule.domain.TaskStatus
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class TaskInstanceResponse(
    val id: UUID,
    val scheduleId: UUID,
    val templateId: UUID,
    val itemId: UUID,
    val unitId: UUID,
    val slotTime: LocalTime?,
    val periodKey: String,
    val scheduledAt: Instant?,
    val dueAt: Instant,
    val photoRequired: Boolean,
    val status: TaskStatus,
)

fun TaskInstance.toResponse() = TaskInstanceResponse(
    id = id!!,
    scheduleId = scheduleId,
    templateId = templateId,
    itemId = itemId,
    unitId = unitId,
    slotTime = slotTime,
    periodKey = periodKey,
    scheduledAt = scheduledAt,
    dueAt = dueAt,
    photoRequired = photoRequired,
    status = status,
)
