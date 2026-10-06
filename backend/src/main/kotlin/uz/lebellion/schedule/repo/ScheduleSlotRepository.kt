package uz.lebellion.schedule.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.schedule.domain.ScheduleSlot
import java.util.UUID

interface ScheduleSlotRepository : JpaRepository<ScheduleSlot, UUID> {

    fun findByScheduleIdOrderBySortOrderAscSlotTimeAsc(scheduleId: UUID): List<ScheduleSlot>

    /** Batch-load the slots of several schedules (to assemble list responses in one query). */
    fun findByScheduleIdIn(scheduleIds: Collection<UUID>): List<ScheduleSlot>
}
