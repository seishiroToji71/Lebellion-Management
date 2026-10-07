package uz.lebellion.submission.domain

/** Why an upload was blocked: an exact SHA-256 match, or a perceptual near-duplicate within threshold. */
enum class BlockedReason {
    EXACT_SHA,
    NEAR_DUPLICATE,
}
