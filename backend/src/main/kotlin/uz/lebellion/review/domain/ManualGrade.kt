package uz.lebellion.review.domain

/**
 * A MANUAL item's grade and its multiplier of the item's `points`:
 * FULL = 1.0, PARTIAL = 0.5, ZERO = 0.0. The awarded score is `round(points * multiplier)`.
 */
enum class ManualGrade(val multiplier: Double) {
    FULL(1.0),
    PARTIAL(0.5),
    ZERO(0.0),
}
