package uz.lebellion.schedule.repo

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import uz.lebellion.schedule.domain.ScheduleSlot
import java.util.UUID

interface ScheduleSlotRepository : JpaRepository<ScheduleSlot, UUID> {

    fun findByScheduleIdOrderBySortOrderAscSlotTimeAsc(scheduleId: UUID): List<ScheduleSlot>

    /** Batch-load the slots of several schedules (to assemble list responses in one query). */
    fun findByScheduleIdIn(scheduleIds: Collection<UUID>): List<ScheduleSlot>

    /**
     * Replace-on-edit: drop a schedule's slots before re-inserting the new set. A bulk DELETE (executed
     * immediately) — not a derived delete — so it runs before the new inserts; otherwise Hibernate orders
     * inserts before deletes and a reused (schedule_id, slot_time) would hit schedule_slot_unique.
     */
    @Modifying
    @Query("delete from ScheduleSlot s where s.scheduleId = :scheduleId")
    fun deleteByScheduleId(@Param("scheduleId") scheduleId: UUID): Int
}
