package uz.lebellion.schedule.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.time.LocalTime
import java.util.UUID

/**
 * A time-of-day slot of a DAILY schedule (local, Asia/Tashkent). `photoRequired` is a nullable override
 * of the item's own setting — null means "use the item's photo_required".
 */
@Entity
@Table(name = "schedule_slot")
class ScheduleSlot(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "schedule_id", nullable = false)
    var scheduleId: UUID,

    @Column(name = "slot_time", nullable = false)
    var slotTime: LocalTime,

    @Column(name = "photo_required")
    var photoRequired: Boolean? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity()
