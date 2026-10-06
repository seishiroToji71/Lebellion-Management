package uz.lebellion.schedule.domain

/**
 * Lifecycle of a task instance. The generator creates [PENDING]; the sweeper marks overdue ones [MISSED];
 * editing/deactivating a schedule moves future PENDING to [CANCELLED] (kept, not deleted, so a late
 * client submission gets a clear error). Visible to users: DONE / MISSED / EXTENDED; the rest are internal.
 */
enum class TaskStatus {
    PENDING,
    SUBMITTED,
    REJECTED,
    EXTENSION_REQUESTED,
    DONE,
    MISSED,
    EXTENDED,
    CANCELLED,
}
