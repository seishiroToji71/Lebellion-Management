package uz.lebellion.schedule.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import uz.lebellion.schedule.domain.Schedule
import uz.lebellion.schedule.domain.ScheduleSlot
import uz.lebellion.schedule.domain.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Create a schedule for a template. DAILY requires `anchorDate` and at least one slot (`intervalDays`
 * counts days from the anchor). WEEKLY ignores `intervalDays`/`anchorDate`/`slots` (forced to a weekly
 * cadence with end-of-Sunday deadline; its template's items are the zones).
 */
data class CreateScheduleRequest(
    val recurrence: Recurrence,
    @field:Min(1) val intervalDays: Int = 1,
    val anchorDate: LocalDate? = null,
    @field:Min(1) val slotWindowMinutes: Int = 60,
    @field:Valid val slots: List<SlotRequest> = emptyList(),
)

data class SlotRequest(
    val slotTime: LocalTime,
    val photoRequired: Boolean? = null,
    val sortOrder: Int = 0,
)

data class ScheduleResponse(
    val id: UUID,
    val organizationId: UUID,
    val templateId: UUID,
    val unitId: UUID,
    val recurrence: Recurrence,
    val intervalDays: Int,
    val anchorDate: LocalDate?,
    val slotWindowMinutes: Int,
    val active: Boolean,
    val slots: List<SlotResponse>,
    val createdAt: Instant,
)

data class SlotResponse(
    val id: UUID,
    val slotTime: LocalTime,
    val photoRequired: Boolean?,
    val sortOrder: Int,
)

fun ScheduleSlot.toResponse() = SlotResponse(id!!, slotTime, photoRequired, sortOrder)

fun Schedule.toResponse(unitId: UUID, slots: List<ScheduleSlot>) = ScheduleResponse(
    id = id!!,
    organizationId = organizationId,
    templateId = templateId,
    unitId = unitId,
    recurrence = recurrence,
    intervalDays = intervalDays,
    anchorDate = anchorDate,
    slotWindowMinutes = slotWindowMinutes,
    active = active,
    slots = slots.map { it.toResponse() },
    createdAt = createdAt,
)
