package uz.lebellion.org.domain

/**
 * Where a unit's effective lead came from, in precedence order:
 * [ACTING_LEAD] -> [LEAD] -> [BRANCH_MANAGER] (fallback) -> [NONE] (no lead and no branch manager).
 */
enum class LeadSource {
    ACTING_LEAD,
    LEAD,
    BRANCH_MANAGER,
    NONE,
}
