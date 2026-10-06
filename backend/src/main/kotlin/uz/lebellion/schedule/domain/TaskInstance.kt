package uz.lebellion.schedule.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * One generated occurrence of a checklist item: belongs to a Unit, is due at `dueAt` (UTC). Occurrence
 * identity is `(scheduleId, itemId, periodKey)` — unique, so regenerating is idempotent. `photoRequired`
 * is the effective value (slot override ?? item). Only PENDING is produced in P2-3.
 */
@Entity
@Table(name = "task_instance")
class TaskInstance(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "schedule_id", nullable = false)
    var scheduleId: UUID,

    @Column(name = "template_id", nullable = false)
    var templateId: UUID,

    @Column(name = "item_id", nullable = false)
    var itemId: UUID,

    @Column(name = "unit_id", nullable = false)
    var unitId: UUID,

    @Column(name = "slot_time")
    var slotTime: LocalTime? = null,

    @Column(name = "period_key", nullable = false, length = 40)
    var periodKey: String,

    @Column(name = "scheduled_at")
    var scheduledAt: Instant? = null,

    @Column(name = "due_at", nullable = false)
    var dueAt: Instant,

    @Column(name = "photo_required", nullable = false)
    var photoRequired: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    var status: TaskStatus = TaskStatus.PENDING,
) : BaseEntity()
