package uz.lebellion.submission.domain

/**
 * Review state of a submission. P2-5 creates [SUBMITTED]; the reviewer drives [ACCEPTED] / [REJECTED]
 * in P2-6. Near-duplicate search ignores photos of [REJECTED] submissions (a legitimate re-shoot after a
 * rejection must not be blocked), while an exact SHA-256 match is rejected regardless of review state.
 */
enum class SubmissionStatus {
    SUBMITTED,
    ACCEPTED,
    REJECTED,
}
