package uz.lebellion.schedule.domain

/**
 * How a schedule recurs:
 * - [DAILY]  — fires at explicit time-of-day slots, every `interval_days` from `anchor_date`.
 * - [WEEKLY] — fires once per ISO calendar week (Asia/Tashkent), deadline = end of Sunday.
 */
enum class Recurrence {
    DAILY,
    WEEKLY,
}
