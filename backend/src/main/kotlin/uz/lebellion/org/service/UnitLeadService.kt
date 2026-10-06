package uz.lebellion.org.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.org.domain.LeadSource
import uz.lebellion.org.domain.OrgUnit
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.web.SetUnitLeadRequest
import uz.lebellion.org.web.UnitLeadResponse
import java.util.UUID

@Service
class UnitLeadService(
    private val units: UnitRepository,
    private val users: AppUserRepository,
) {

    /**
     * Replace a unit's lead/acting_lead. FOUNDER may target any unit; a BRANCH_MANAGER only units of their
     * own branch. Each referenced user must be active and belong to the unit (or manage its branch).
     */
    @Transactional
    fun setLead(principal: AuthPrincipal, unitId: UUID, req: SetUnitLeadRequest): UnitLeadResponse {
        requireFounderOrManager(principal)
        val unit = resolveUnitInScope(principal, unitId)
        requireAssignable(principal.organizationId, unit, req.leadEmployeeId)
        requireAssignable(principal.organizationId, unit, req.actingLeadEmployeeId)
        unit.leadEmployeeId = req.leadEmployeeId
        unit.actingLeadEmployeeId = req.actingLeadEmployeeId
        return response(units.save(unit))
    }

    /** The unit's current assignment and resolved effective lead. Same scoping as [setLead]. */
    @Transactional(readOnly = true)
    fun getLead(principal: AuthPrincipal, unitId: UUID): UnitLeadResponse {
        requireFounderOrManager(principal)
        return response(resolveUnitInScope(principal, unitId))
    }

    private fun resolveUnitInScope(principal: AuthPrincipal, unitId: UUID): OrgUnit {
        val unit = units.findByIdAndOrganizationId(unitId, principal.organizationId)
            ?: throw NotFoundException("unit not found")
        val scope = branchScopeOf(principal)
        if (scope != null && unit.branchId != scope) throw NotFoundException("unit not found")
        return unit
    }

    private fun requireAssignable(orgId: UUID, unit: OrgUnit, userId: UUID?) {
        if (userId == null) return
        val user = users.findByIdAndOrganizationId(userId, orgId)
            ?: throw RequestValidationException("user is not in this organization")
        if (!user.isActive) throw RequestValidationException("user is not active")
        val memberOfUnit = user.unitId == unit.id
        val managesBranch = user.role == Role.BRANCH_MANAGER && user.branchId == unit.branchId
        if (!memberOfUnit && !managesBranch) {
            throw RequestValidationException("user must belong to the unit or manage its branch")
        }
    }

    private fun response(unit: OrgUnit): UnitLeadResponse {
        val (effective, source) = resolveEffective(unit)
        return UnitLeadResponse(
            unitId = unit.id!!,
            leadEmployeeId = unit.leadEmployeeId,
            actingLeadEmployeeId = unit.actingLeadEmployeeId,
            effectiveLeadUserId = effective,
            source = source,
        )
    }

    private fun resolveEffective(unit: OrgUnit): Pair<UUID?, LeadSource> {
        activeUserId(unit.organizationId, unit.actingLeadEmployeeId)?.let { return it to LeadSource.ACTING_LEAD }
        activeUserId(unit.organizationId, unit.leadEmployeeId)?.let { return it to LeadSource.LEAD }
        val manager = users.findFirstByOrganizationIdAndBranchIdAndRoleAndIsActiveTrueOrderByCreatedAtAsc(
            unit.organizationId, unit.branchId, Role.BRANCH_MANAGER,
        )
        return if (manager != null) manager.id to LeadSource.BRANCH_MANAGER else null to LeadSource.NONE
    }

    /** The id, but only if the user still exists and is active (a deactivated lead falls through). */
    private fun activeUserId(orgId: UUID, id: UUID?): UUID? {
        if (id == null) return null
        val user = users.findByIdAndOrganizationId(id, orgId) ?: return null
        return if (user.isActive) user.id else null
    }
}
