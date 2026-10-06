package uz.lebellion.schedule.domain

/**
 * Lifecycle of a task instance. The generator (P2-3) only creates [PENDING]; the transitions and the
 * MISSED sweeper arrive in P2-4. Visible to users: DONE / MISSED / EXTENDED; the rest are internal.
 */
enum class TaskStatus {
    PENDING,
    SUBMITTED,
    REJECTED,
    EXTENSION_REQUESTED,
    DONE,
    MISSED,
    EXTENDED,
}
