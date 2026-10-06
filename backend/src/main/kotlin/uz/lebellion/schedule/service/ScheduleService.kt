package uz.lebellion.schedule.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.checklist.repo.ChecklistTemplateRepository
import uz.lebellion.checklist.service.ensureUnitInScope
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.service.requireFounderOrManager
import uz.lebellion.schedule.domain.Recurrence
import uz.lebellion.schedule.domain.Schedule
import uz.lebellion.schedule.domain.ScheduleSlot
import uz.lebellion.schedule.repo.ScheduleRepository
import uz.lebellion.schedule.repo.ScheduleSlotRepository
import uz.lebellion.schedule.web.CreateScheduleRequest
import uz.lebellion.schedule.web.ScheduleResponse
import uz.lebellion.schedule.web.toResponse
import java.util.UUID

@Service
class ScheduleService(
    private val schedules: ScheduleRepository,
    private val slots: ScheduleSlotRepository,
    private val templates: ChecklistTemplateRepository,
    private val units: UnitRepository,
) {

    /** Create a schedule for a template. FOUNDER/BRANCH_MANAGER, scoped via the template's unit. */
    @Transactional
    fun create(principal: AuthPrincipal, templateId: UUID, req: CreateScheduleRequest): ScheduleResponse {
        requireFounderOrManager(principal)
        val template = templates.findByIdAndOrganizationId(templateId, principal.organizationId)
            ?: throw NotFoundException("template not found")
        ensureUnitInScope(units, principal, template.unitId)
        validate(req)

        val weekly = req.recurrence == Recurrence.WEEKLY
        val schedule = schedules.save(
            Schedule(
                organizationId = principal.organizationId,
                templateId = templateId,
                recurrence = req.recurrence,
                intervalDays = if (weekly) 1 else req.intervalDays,
                anchorDate = if (weekly) null else req.anchorDate,
                slotWindowMinutes = req.slotWindowMinutes,
            ),
        )
        val savedSlots = if (weekly) {
            emptyList()
        } else {
            req.slots.map {
                slots.save(
                    ScheduleSlot(
                        organizationId = principal.organizationId,
                        scheduleId = schedule.id!!,
                        slotTime = it.slotTime,
                        photoRequired = it.photoRequired,
                        sortOrder = it.sortOrder,
                    ),
                )
            }
        }
        return schedule.toResponse(template.unitId, savedSlots)
    }

    /** A template's schedules (bounded list), with each schedule's slots. Same scoping as [create]. */
    @Transactional(readOnly = true)
    fun list(principal: AuthPrincipal, templateId: UUID): List<ScheduleResponse> {
        requireFounderOrManager(principal)
        val template = templates.findByIdAndOrganizationId(templateId, principal.organizationId)
            ?: throw NotFoundException("template not found")
        ensureUnitInScope(units, principal, template.unitId)

        val rows = schedules.findByOrganizationIdAndTemplateIdOrderByCreatedAtAscIdAsc(principal.organizationId, templateId)
        val slotsBySchedule = if (rows.isEmpty()) {
            emptyMap()
        } else {
            slots.findByScheduleIdIn(rows.map { it.id!! }).groupBy { it.scheduleId }
        }
        return rows.map { schedule ->
            val scheduleSlots = (slotsBySchedule[schedule.id!!] ?: emptyList())
                .sortedWith(compareBy({ it.sortOrder }, { it.slotTime }))
            schedule.toResponse(template.unitId, scheduleSlots)
        }
    }

    private fun validate(req: CreateScheduleRequest) {
        if (req.slotWindowMinutes < 1) throw RequestValidationException("slotWindowMinutes must be >= 1")
        if (req.intervalDays < 1) throw RequestValidationException("intervalDays must be >= 1")
        if (req.recurrence == Recurrence.DAILY) {
            if (req.anchorDate == null) throw RequestValidationException("anchorDate is required for a DAILY schedule")
            if (req.slots.isEmpty()) throw RequestValidationException("a DAILY schedule needs at least one slot")
        }
    }
}
