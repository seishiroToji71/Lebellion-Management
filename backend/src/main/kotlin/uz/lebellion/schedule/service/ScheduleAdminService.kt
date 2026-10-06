package uz.lebellion.schedule.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.checklist.repo.ChecklistTemplateRepository
import uz.lebellion.checklist.service.ensureUnitInScope
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.service.requireFounderOrManager
import uz.lebellion.schedule.domain.Recurrence
import uz.lebellion.schedule.domain.Schedule
import uz.lebellion.schedule.domain.ScheduleSlot
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.repo.ScheduleRepository
import uz.lebellion.schedule.repo.ScheduleSlotRepository
import uz.lebellion.schedule.repo.TaskInstanceRepository
import uz.lebellion.schedule.web.ScheduleResponse
import uz.lebellion.schedule.web.UpdateScheduleRequest
import uz.lebellion.schedule.web.toResponse
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Edit / deactivate a schedule with "regenerate forward": future PENDING instances are moved to
 * CANCELLED (never deleted), then — for an edit that stays active — recreated from the new definition.
 * Already-acted-upon and past instances are untouched.
 */
@Service
class ScheduleAdminService(
    private val schedules: ScheduleRepository,
    private val slots: ScheduleSlotRepository,
    private val templates: ChecklistTemplateRepository,
    private val units: UnitRepository,
    private val taskInstances: TaskInstanceRepository,
    private val generator: ScheduleGenerator,
    private val audit: AuditLogRecorder,
    private val clock: Clock,
) {

    @Transactional
    fun update(principal: AuthPrincipal, scheduleId: UUID, req: UpdateScheduleRequest): ScheduleResponse {
        val (schedule, unitId) = resolveInScope(principal, scheduleId)
        validateScheduleShape(schedule.recurrence, req.anchorDate, req.slots.size, req.slotWindowMinutes, req.intervalDays)

        val weekly = schedule.recurrence == Recurrence.WEEKLY
        schedule.intervalDays = if (weekly) 1 else req.intervalDays
        schedule.anchorDate = if (weekly) null else req.anchorDate
        schedule.slotWindowMinutes = req.slotWindowMinutes
        schedule.active = req.active
        schedules.save(schedule)

        val savedSlots = if (weekly) {
            slots.deleteByScheduleId(scheduleId)
            emptyList()
        } else {
            slots.deleteByScheduleId(scheduleId)
            req.slots.map {
                slots.save(
                    ScheduleSlot(
                        organizationId = schedule.organizationId,
                        scheduleId = scheduleId,
                        slotTime = it.slotTime,
                        photoRequired = it.photoRequired,
                        sortOrder = it.sortOrder,
                    ),
                )
            }
        }

        val now = Instant.now(clock)
        val cancelled = taskInstances.cancelFuturePending(scheduleId, TaskStatus.PENDING, TaskStatus.CANCELLED, now)
        val regenerated = if (schedule.active) generator.generate(schedule) else 0
        audit.record(
            organizationId = schedule.organizationId,
            eventType = "SCHEDULE_EDITED",
            actorUserId = principal.userId,
            targetType = "SCHEDULE",
            targetId = scheduleId,
            metadata = mapOf("cancelled" to cancelled, "regenerated" to regenerated, "active" to schedule.active),
        )
        return schedule.toResponse(unitId, savedSlots)
    }

    @Transactional
    fun deactivate(principal: AuthPrincipal, scheduleId: UUID): ScheduleResponse {
        val (schedule, unitId) = resolveInScope(principal, scheduleId)
        schedule.active = false
        schedules.save(schedule)

        val now = Instant.now(clock)
        val cancelled = taskInstances.cancelFuturePending(scheduleId, TaskStatus.PENDING, TaskStatus.CANCELLED, now)
        audit.record(
            organizationId = schedule.organizationId,
            eventType = "SCHEDULE_DEACTIVATED",
            actorUserId = principal.userId,
            targetType = "SCHEDULE",
            targetId = scheduleId,
            metadata = mapOf("cancelled" to cancelled),
        )
        val currentSlots = slots.findByScheduleIdOrderBySortOrderAscSlotTimeAsc(scheduleId)
        return schedule.toResponse(unitId, currentSlots)
    }

    /** Resolve a schedule inside the caller's tenant + branch scope; returns it with its unit id. */
    private fun resolveInScope(principal: AuthPrincipal, scheduleId: UUID): Pair<Schedule, UUID> {
        requireFounderOrManager(principal)
        val schedule = schedules.findByIdAndOrganizationId(scheduleId, principal.organizationId)
            ?: throw NotFoundException("schedule not found")
        val template = templates.findByIdAndOrganizationId(schedule.templateId, principal.organizationId)
            ?: throw NotFoundException("schedule not found")
        ensureUnitInScope(units, principal, template.unitId)
        return schedule to template.unitId
    }
}
