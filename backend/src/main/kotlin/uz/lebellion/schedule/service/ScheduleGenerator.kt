package uz.lebellion.schedule.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.checklist.domain.ChecklistItem
import uz.lebellion.checklist.repo.ChecklistItemRepository
import uz.lebellion.checklist.repo.ChecklistTemplateRepository
import uz.lebellion.schedule.config.ScheduleProperties
import uz.lebellion.schedule.domain.Recurrence
import uz.lebellion.schedule.domain.Schedule
import uz.lebellion.schedule.domain.ScheduleSlot
import uz.lebellion.schedule.domain.TaskInstance
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.repo.ScheduleRepository
import uz.lebellion.schedule.repo.ScheduleSlotRepository
import uz.lebellion.schedule.repo.TaskInstanceRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.UUID

/** Schedule logic runs on Asia/Tashkent (UTC+5, no DST); instants are stored in UTC. */
private val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")
private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Materialises PENDING [TaskInstance] rows for every active schedule over the next `horizonDays`.
 * Idempotent: an occurrence already present (unique `scheduleId+itemId+periodKey`) is skipped, so it is
 * safe to run repeatedly. "now" comes from an injected [Clock] so tests can time-travel deterministically.
 */
@Service
class ScheduleGenerator(
    private val schedules: ScheduleRepository,
    private val slots: ScheduleSlotRepository,
    private val templates: ChecklistTemplateRepository,
    private val items: ChecklistItemRepository,
    private val taskInstances: TaskInstanceRepository,
    private val props: ScheduleProperties,
    private val clock: Clock,
) {

    /** Generate for all active schedules; returns the number of task instances created. */
    @Transactional
    fun generate(): Int {
        val now = Instant.now(clock)
        return schedules.findByActiveTrue().sumOf { generateFor(it, now) }
    }

    /** Generate for a single schedule (used after an edit regenerates its cancelled-forward window). */
    @Transactional
    fun generate(schedule: Schedule): Int = generateFor(schedule, Instant.now(clock))

    private fun generateFor(schedule: Schedule, now: Instant): Int {
        val lines = items.findByOrganizationIdAndTemplateIdOrderBySortOrderAscIdAsc(schedule.organizationId, schedule.templateId)
        if (lines.isEmpty()) return 0
        val template = templates.findByIdAndOrganizationId(schedule.templateId, schedule.organizationId) ?: return 0
        return when (schedule.recurrence) {
            Recurrence.DAILY -> generateDaily(schedule, lines, template.unitId, now)
            Recurrence.WEEKLY -> generateWeekly(schedule, lines, template.unitId, now)
        }
    }

    private fun generateDaily(schedule: Schedule, lines: List<ChecklistItem>, unitId: UUID, now: Instant): Int {
        val anchor = schedule.anchorDate ?: return 0
        val daySlots = slots.findByScheduleIdOrderBySortOrderAscSlotTimeAsc(schedule.id!!)
        if (daySlots.isEmpty()) return 0
        val window = Duration.ofMinutes(schedule.slotWindowMinutes.toLong())
        var count = 0
        var date = now.atZone(TASHKENT).toLocalDate()
        val end = now.plus(Duration.ofDays(props.horizonDays)).atZone(TASHKENT).toLocalDate()
        while (!date.isAfter(end)) {
            if (Math.floorMod(ChronoUnit.DAYS.between(anchor, date), schedule.intervalDays.toLong()) == 0L) {
                for (slot in daySlots) {
                    val scheduledAt = date.atTime(slot.slotTime).atZone(TASHKENT).toInstant()
                    val dueAt = scheduledAt.plus(window)
                    if (dueAt.isBefore(now)) continue // the window has already closed — don't backfill
                    val periodKey = "D:$date:${slot.slotTime.format(HHMM)}"
                    count += lines.count { line ->
                        saveIfAbsent(schedule, unitId, line, periodKey, slot.slotTime, scheduledAt, dueAt, slot.photoRequired ?: line.photoRequired)
                    }
                }
            }
            date = date.plusDays(1)
        }
        return count
    }

    private fun generateWeekly(schedule: Schedule, lines: List<ChecklistItem>, unitId: UUID, now: Instant): Int {
        var count = 0
        val end = now.plus(Duration.ofDays(props.horizonDays)).atZone(TASHKENT).toLocalDate()
        var monday = now.atZone(TASHKENT).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        while (!monday.isAfter(end)) {
            // deadline = end of Sunday = next Monday 00:00 local (so all of Sunday is included)
            val dueAt = monday.plusDays(7).atStartOfDay(TASHKENT).toInstant()
            if (!dueAt.isBefore(now)) {
                val periodKey = weekKey(monday)
                count += lines.count { line ->
                    saveIfAbsent(schedule, unitId, line, periodKey, null, null, dueAt, line.photoRequired)
                }
            }
            monday = monday.plusDays(7)
        }
        return count
    }

    private fun saveIfAbsent(
        schedule: Schedule,
        unitId: UUID,
        line: ChecklistItem,
        periodKey: String,
        slotTime: java.time.LocalTime?,
        scheduledAt: Instant?,
        dueAt: Instant,
        photoRequired: Boolean,
    ): Boolean {
        // ignore CANCELLED rows so a cancelled-forward occurrence is regenerated as a fresh PENDING
        if (taskInstances.existsByScheduleIdAndItemIdAndPeriodKeyAndStatusNot(schedule.id!!, line.id!!, periodKey, TaskStatus.CANCELLED)) return false
        taskInstances.save(
            TaskInstance(
                organizationId = schedule.organizationId,
                scheduleId = schedule.id!!,
                templateId = schedule.templateId,
                itemId = line.id!!,
                unitId = unitId,
                slotTime = slotTime,
                periodKey = periodKey,
                scheduledAt = scheduledAt,
                dueAt = dueAt,
                photoRequired = photoRequired,
                status = TaskStatus.PENDING,
            ),
        )
        return true
    }

    private fun weekKey(monday: LocalDate): String =
        "W:%04d-W%02d".format(monday.get(WeekFields.ISO.weekBasedYear()), monday.get(WeekFields.ISO.weekOfWeekBasedYear()))
}
