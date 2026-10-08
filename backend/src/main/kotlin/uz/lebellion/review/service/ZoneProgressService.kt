package uz.lebellion.review.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.checklist.repo.ChecklistTemplateRepository
import uz.lebellion.checklist.service.ensureUnitInScope
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.service.requireFounderOrManager
import uz.lebellion.review.web.ZoneProgressResponse
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.repo.ScheduleRepository
import uz.lebellion.schedule.repo.TaskInstanceRepository
import java.util.UUID

/** "N of M" progress for one (schedule, period): DONE occurrences over the total non-cancelled ones. */
@Service
class ZoneProgressService(
    private val schedules: ScheduleRepository,
    private val templates: ChecklistTemplateRepository,
    private val units: UnitRepository,
    private val taskInstances: TaskInstanceRepository,
) {

    @Transactional(readOnly = true)
    fun progress(principal: AuthPrincipal, scheduleId: UUID, periodKey: String): ZoneProgressResponse {
        requireFounderOrManager(principal)
        val schedule = schedules.findByIdAndOrganizationId(scheduleId, principal.organizationId)
            ?: throw NotFoundException("schedule not found")
        val template = templates.findByIdAndOrganizationId(schedule.templateId, principal.organizationId)
            ?: throw NotFoundException("schedule not found")
        ensureUnitInScope(units, principal, template.unitId)

        val total = taskInstances.countByScheduleIdAndPeriodKeyAndStatusNot(scheduleId, periodKey, TaskStatus.CANCELLED)
        val done = taskInstances.countByScheduleIdAndPeriodKeyAndStatus(scheduleId, periodKey, TaskStatus.DONE)
        return ZoneProgressResponse(scheduleId, periodKey, total, done)
    }
}
