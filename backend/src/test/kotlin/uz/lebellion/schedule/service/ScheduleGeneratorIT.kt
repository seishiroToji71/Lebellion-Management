package uz.lebellion.schedule.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import uz.lebellion.auth.domain.Organization
import uz.lebellion.auth.repo.OrganizationRepository
import uz.lebellion.auth.support.MutableClock
import uz.lebellion.auth.support.MutableClockConfig
import uz.lebellion.checklist.domain.ChecklistItem
import uz.lebellion.checklist.domain.ChecklistTemplate
import uz.lebellion.checklist.domain.ItemType
import uz.lebellion.checklist.repo.ChecklistItemRepository
import uz.lebellion.checklist.repo.ChecklistTemplateRepository
import uz.lebellion.org.domain.Branch
import uz.lebellion.org.domain.OrgUnit
import uz.lebellion.org.repo.BranchRepository
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.schedule.domain.Recurrence
import uz.lebellion.schedule.domain.Schedule
import uz.lebellion.schedule.domain.ScheduleSlot
import uz.lebellion.schedule.repo.ScheduleRepository
import uz.lebellion.schedule.repo.ScheduleSlotRepository
import uz.lebellion.schedule.repo.TaskInstanceRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.UUID

/**
 * Generation logic (P2-3): every-other-day with a month-boundary crossing, a 23:xx slot whose window
 * spills past midnight, the WEEKLY end-of-Sunday deadline, and idempotency. Seeds entities directly and
 * time-travels via [MutableClock]; the generator reads "now" from the injected Clock.
 */
@SpringBootTest
@Testcontainers
@Import(MutableClockConfig::class)
class ScheduleGeneratorIT {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
        private val TZ: ZoneId = ZoneId.of("Asia/Tashkent")
    }

    @Autowired lateinit var orgs: OrganizationRepository
    @Autowired lateinit var branches: BranchRepository
    @Autowired lateinit var units: UnitRepository
    @Autowired lateinit var templates: ChecklistTemplateRepository
    @Autowired lateinit var items: ChecklistItemRepository
    @Autowired lateinit var schedules: ScheduleRepository
    @Autowired lateinit var slots: ScheduleSlotRepository
    @Autowired lateinit var taskInstances: TaskInstanceRepository
    @Autowired lateinit var generator: ScheduleGenerator
    @Autowired lateinit var clock: MutableClock

    @BeforeEach
    fun clean() {
        // isolate each test: generate() scans ALL active schedules, so start with none
        taskInstances.deleteAll()
        slots.deleteAll()
        schedules.deleteAll()
    }

    private fun instant(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDate.of(y, mo, d).atTime(h, mi).atZone(TZ).toInstant()

    private fun orgId(): UUID = orgs.save(Organization("Org-${UUID.randomUUID()}")).id!!

    private fun unitId(org: UUID): UUID {
        val branch = branches.save(Branch(organizationId = org, name = "B")).id!!
        return units.save(OrgUnit(organizationId = org, branchId = branch, name = "U")).id!!
    }

    private fun templateId(org: UUID, unit: UUID): UUID =
        templates.save(ChecklistTemplate(organizationId = org, unitId = unit, name = "T")).id!!

    private fun addItem(org: UUID, template: UUID, title: String, photoRequired: Boolean = true): UUID =
        items.save(
            ChecklistItem(
                organizationId = org,
                templateId = template,
                type = ItemType.PHOTO,
                titleRu = title,
                titleUz = title,
                photoRequired = photoRequired,
            ),
        ).id!!

    private fun daily(org: UUID, template: UUID, anchor: LocalDate, interval: Int, window: Int, vararg slotTimes: LocalTime): UUID {
        val schedule = schedules.save(
            Schedule(
                organizationId = org,
                templateId = template,
                recurrence = Recurrence.DAILY,
                intervalDays = interval,
                anchorDate = anchor,
                slotWindowMinutes = window,
            ),
        ).id!!
        slotTimes.forEachIndexed { i, t ->
            slots.save(ScheduleSlot(organizationId = org, scheduleId = schedule, slotTime = t, sortOrder = i))
        }
        return schedule
    }

    private fun weekly(org: UUID, template: UUID): UUID =
        schedules.save(Schedule(organizationId = org, templateId = template, recurrence = Recurrence.WEEKLY)).id!!

    @Test
    fun `every-other-day keeps parity across a month boundary`() {
        val org = orgId()
        val template = templateId(org, unitId(org))
        addItem(org, template, "trap")
        // anchor Oct 30, every 2 days, one slot at 10:00
        val schedule = daily(org, template, LocalDate.of(2026, 10, 30), interval = 2, window = 60, LocalTime.of(10, 0))

        clock.setTo(instant(2026, 10, 29, 6, 0)) // horizon default 14 days -> through Nov 12
        generator.generate()

        val dates = taskInstances.findByScheduleId(schedule).map { it.scheduledAt!!.atZone(TZ).toLocalDate() }.toSet()
        assertTrue(dates.contains(LocalDate.of(2026, 10, 30)), "anchor day")
        assertTrue(dates.contains(LocalDate.of(2026, 11, 1)), "parity preserved across Oct->Nov")
        assertTrue(dates.contains(LocalDate.of(2026, 11, 3)))
        assertFalse(dates.contains(LocalDate.of(2026, 10, 31)), "odd offset must be skipped")
        assertFalse(dates.contains(LocalDate.of(2026, 11, 2)))
        assertFalse(dates.contains(LocalDate.of(2026, 10, 29)), "day before anchor is odd offset")
    }

    @Test
    fun `a 23 00 slot with a 60-minute window is due at 00 00 the next local day`() {
        val org = orgId()
        val template = templateId(org, unitId(org))
        addItem(org, template, "close")
        val schedule = daily(org, template, LocalDate.of(2026, 10, 10), interval = 1, window = 60, LocalTime.of(23, 0))

        clock.setTo(instant(2026, 10, 10, 6, 0))
        generator.generate()

        val oct10 = taskInstances.findByScheduleId(schedule)
            .first { it.scheduledAt!!.atZone(TZ).toLocalDate() == LocalDate.of(2026, 10, 10) }
        assertEquals(LocalTime.of(23, 0), oct10.scheduledAt!!.atZone(TZ).toLocalTime())
        val due = oct10.dueAt.atZone(TZ)
        assertEquals(LocalDate.of(2026, 10, 11), due.toLocalDate(), "window crosses midnight")
        assertEquals(LocalTime.of(0, 0), due.toLocalTime())
    }

    @Test
    fun `weekly deadline is the end of Sunday and zones carry no slot`() {
        val org = orgId()
        val template = templateId(org, unitId(org))
        addItem(org, template, "floor")
        addItem(org, template, "walls")
        val schedule = weekly(org, template)

        val now = LocalDate.of(2026, 10, 7)
        clock.setTo(instant(2026, 10, 7, 12, 0))
        generator.generate()

        val insts = taskInstances.findByScheduleId(schedule)
        val monday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val currentWeekDue = monday.plusDays(7).atStartOfDay(TZ).toInstant() // next Monday 00:00 = end of Sunday
        assertEquals(currentWeekDue, insts.minOf { it.dueAt }, "soonest due = end of current Sunday")
        assertTrue(insts.all { it.slotTime == null && it.scheduledAt == null }, "weekly zones have no slot")
        assertTrue(insts.size % 2 == 0, "two zones per generated week")
    }

    @Test
    fun `weekly generation spans the ISO year boundary (W53 2026) without gaps or duplicates`() {
        val org = orgId()
        val template = templateId(org, unitId(org))
        addItem(org, template, "deep-clean")
        val schedule = weekly(org, template)

        clock.setTo(instant(2026, 12, 30, 12, 0)) // Wednesday of ISO week 53 / 2026 (a long ISO year)
        generator.generate()

        val insts = taskInstances.findByScheduleId(schedule)
        val keys = insts.map { it.periodKey }

        // expected: exactly one instance per ISO week from the current week's Monday through the horizon
        val now = LocalDate.of(2026, 12, 30)
        val end = now.plusDays(14)
        val expectedKeys = generateSequence(now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) { it.plusDays(7) }
            .takeWhile { !it.isAfter(end) }
            .map { "W:%04d-W%02d".format(it.get(WeekFields.ISO.weekBasedYear()), it.get(WeekFields.ISO.weekOfWeekBasedYear())) }
            .toList()

        assertTrue(expectedKeys.contains("W:2026-W53"), "2026 is a long ISO year and must have a week 53")
        assertTrue(expectedKeys.contains("W:2027-W01"), "the next week rolls into ISO year 2027")
        assertEquals(expectedKeys.toSet(), keys.toSet(), "every ISO week in range is covered — no gaps across the boundary")
        assertEquals(keys.size, keys.toSet().size, "no duplicate period_key across the year boundary")

        // W53's deadline is the end of Sunday 2027-01-03 = 2027-01-04 00:00 local (Asia/Tashkent)
        val w53 = insts.first { it.periodKey == "W:2026-W53" }
        assertEquals(LocalDate.of(2027, 1, 4).atStartOfDay(TZ).toInstant(), w53.dueAt)

        generator.generate()
        assertEquals(insts.size, taskInstances.findByScheduleId(schedule).size, "idempotent across the boundary")
    }

    @Test
    fun `generation is idempotent`() {
        val org = orgId()
        val template = templateId(org, unitId(org))
        addItem(org, template, "mop")
        val schedule = daily(org, template, LocalDate.of(2026, 10, 1), interval = 1, window = 60, LocalTime.of(9, 0))

        clock.setTo(instant(2026, 10, 1, 6, 0))
        generator.generate()
        val afterFirst = taskInstances.findByScheduleId(schedule).size
        assertTrue(afterFirst > 0)

        generator.generate()
        assertEquals(afterFirst, taskInstances.findByScheduleId(schedule).size, "re-running creates no duplicates")
    }
}
