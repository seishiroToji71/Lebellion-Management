package uz.lebellion.checklist.service

import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.org.domain.OrgUnit
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.service.branchScopeOf
import java.util.UUID

/**
 * Resolves a unit inside the caller's tenant AND branch scope. A FOUNDER reaches any unit in the org; a
 * BRANCH_MANAGER only units of their own branch. A unit outside scope (or in another org) is reported as
 * 404 — never revealing that it exists. Reuses the org module's [branchScopeOf].
 */
internal fun ensureUnitInScope(units: UnitRepository, principal: AuthPrincipal, unitId: UUID): OrgUnit {
    val unit = units.findByIdAndOrganizationId(unitId, principal.organizationId)
        ?: throw NotFoundException("unit not found")
    val scope = branchScopeOf(principal)
    if (scope != null && unit.branchId != scope) throw NotFoundException("unit not found")
    return unit
}
