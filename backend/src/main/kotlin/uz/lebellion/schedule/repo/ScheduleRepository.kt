package uz.lebellion.schedule.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.schedule.domain.Schedule
import java.util.UUID

interface ScheduleRepository : JpaRepository<Schedule, UUID> {

    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): Schedule?

    /** A template's schedules, oldest first — a bounded list (returned whole, not paginated). */
    fun findByOrganizationIdAndTemplateIdOrderByCreatedAtAscIdAsc(organizationId: UUID, templateId: UUID): List<Schedule>

    /** All active schedules across orgs — the generator's work list (a system job, not tenant-scoped). */
    fun findByActiveTrue(): List<Schedule>
}
