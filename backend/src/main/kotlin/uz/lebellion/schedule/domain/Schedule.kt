package uz.lebellion.schedule.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.time.LocalDate
import java.util.UUID

/**
 * A recurrence that drives one checklist template. DAILY uses `anchorDate` + `intervalDays` + slots
 * (see [ScheduleSlot]); WEEKLY fixes `intervalDays` to 1, has no slots, and its deadline is end of Sunday.
 * `slotWindowMinutes` is the allowed completion window after a DAILY slot's time (default 60).
 */
@Entity
@Table(name = "schedule")
class Schedule(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "template_id", nullable = false)
    var templateId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence", nullable = false, length = 16)
    var recurrence: Recurrence,

    @Column(name = "interval_days", nullable = false)
    var intervalDays: Int = 1,

    @Column(name = "anchor_date")
    var anchorDate: LocalDate? = null,

    @Column(name = "slot_window_minutes", nullable = false)
    var slotWindowMinutes: Int = 60,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,
) : BaseEntity()
