package uz.lebellion.schedule.repo

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import uz.lebellion.schedule.domain.TaskInstance
import uz.lebellion.schedule.domain.TaskStatus
import java.time.Instant
import java.util.UUID

interface TaskInstanceRepository : JpaRepository<TaskInstance, UUID> {

    /**
     * Idempotency guard for the generator. Ignores CANCELLED rows (the partial unique index does too), so
     * an occurrence whose only row is CANCELLED is regenerated as a fresh PENDING.
     */
    fun existsByScheduleIdAndItemIdAndPeriodKeyAndStatusNot(
        scheduleId: UUID,
        itemId: UUID,
        periodKey: String,
        status: TaskStatus,
    ): Boolean

    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): TaskInstance?

    fun findByScheduleId(scheduleId: UUID): List<TaskInstance>

    /** Overdue PENDING instances — the MISSED sweeper's work list. */
    fun findByStatusAndDueAtLessThanEqual(status: TaskStatus, dueAt: Instant): List<TaskInstance>

    /** Cancel-forward: move this schedule's still-open future instances to CANCELLED. Returns the count. */
    @Modifying
    @Query(
        """
        update TaskInstance t set t.status = :cancelled
         where t.scheduleId = :scheduleId and t.status = :pending and t.dueAt > :now
        """,
    )
    fun cancelFuturePending(
        @Param("scheduleId") scheduleId: UUID,
        @Param("pending") pending: TaskStatus,
        @Param("cancelled") cancelled: TaskStatus,
        @Param("now") now: Instant,
    ): Int

    /** A unit's instances due within `[from, to)`, soonest first — the "upcoming tasks" feed (hides CANCELLED). */
    fun findByOrganizationIdAndUnitIdAndStatusNotAndDueAtGreaterThanEqualAndDueAtLessThanOrderByDueAtAscIdAsc(
        organizationId: UUID,
        unitId: UUID,
        status: TaskStatus,
        from: Instant,
        to: Instant,
    ): List<TaskInstance>
}
