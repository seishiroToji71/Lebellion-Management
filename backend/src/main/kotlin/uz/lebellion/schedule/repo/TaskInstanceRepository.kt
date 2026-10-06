package uz.lebellion.schedule.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.schedule.domain.TaskInstance
import java.time.Instant
import java.util.UUID

interface TaskInstanceRepository : JpaRepository<TaskInstance, UUID> {

    /** Idempotency guard for the generator (unique on `(scheduleId, itemId, periodKey)`). */
    fun existsByScheduleIdAndItemIdAndPeriodKey(scheduleId: UUID, itemId: UUID, periodKey: String): Boolean

    fun findByScheduleId(scheduleId: UUID): List<TaskInstance>

    /** A unit's instances due within `[from, to)`, soonest first — the "upcoming tasks" feed. */
    fun findByOrganizationIdAndUnitIdAndDueAtGreaterThanEqualAndDueAtLessThanOrderByDueAtAscIdAsc(
        organizationId: UUID,
        unitId: UUID,
        from: Instant,
        to: Instant,
    ): List<TaskInstance>
}
