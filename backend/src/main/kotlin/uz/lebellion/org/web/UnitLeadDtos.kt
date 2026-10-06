package uz.lebellion.org.web

import uz.lebellion.org.domain.LeadSource
import java.util.UUID

/**
 * Replace a unit's lead assignment. Both fields are the full desired state: null clears that role. The
 * referenced user must be active and either a member of the unit or the branch's manager.
 */
data class SetUnitLeadRequest(
    val leadEmployeeId: UUID? = null,
    val actingLeadEmployeeId: UUID? = null,
)

data class UnitLeadResponse(
    val unitId: UUID,
    val leadEmployeeId: UUID?,
    val actingLeadEmployeeId: UUID?,
    /** Resolved acting_lead -> lead -> branch manager; null only when [source] is NONE. */
    val effectiveLeadUserId: UUID?,
    val source: LeadSource,
)
