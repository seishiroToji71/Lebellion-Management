package uz.lebellion.schedule.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import java.time.LocalDate

/**
 * Edit a schedule. `recurrence` is immutable (kept from the existing schedule); everything else is the
 * new desired state. Applying an edit cancels this schedule's future PENDING instances and — if still
 * active — regenerates them from the new definition.
 */
data class UpdateScheduleRequest(
    @field:Min(1) val intervalDays: Int = 1,
    val anchorDate: LocalDate? = null,
    @field:Min(1) val slotWindowMinutes: Int = 60,
    val active: Boolean = true,
    @field:Valid val slots: List<SlotRequest> = emptyList(),
)
