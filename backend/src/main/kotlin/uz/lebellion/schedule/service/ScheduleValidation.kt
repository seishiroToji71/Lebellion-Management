package uz.lebellion.schedule.service

import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.schedule.domain.Recurrence
import java.time.LocalDate

/** Shared shape check for create and edit: DAILY needs an anchor and >= 1 slot; bounds are positive. */
internal fun validateScheduleShape(
    recurrence: Recurrence,
    anchorDate: LocalDate?,
    slotCount: Int,
    slotWindowMinutes: Int,
    intervalDays: Int,
) {
    if (slotWindowMinutes < 1) throw RequestValidationException("slotWindowMinutes must be >= 1")
    if (intervalDays < 1) throw RequestValidationException("intervalDays must be >= 1")
    if (recurrence == Recurrence.DAILY) {
        if (anchorDate == null) throw RequestValidationException("anchorDate is required for a DAILY schedule")
        if (slotCount == 0) throw RequestValidationException("a DAILY schedule needs at least one slot")
    }
}
