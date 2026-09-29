package uz.lebellion.auth.domain

/** Stored invite states. EXPIRED is computed from expires_at, never persisted. */
enum class InviteStatus {
    PENDING,
    USED,
    REVOKED,
}
