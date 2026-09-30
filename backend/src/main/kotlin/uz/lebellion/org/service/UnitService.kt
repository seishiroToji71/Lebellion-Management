package uz.lebellion.org.service

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.common.web.Page
import uz.lebellion.common.web.pageOf
import uz.lebellion.org.domain.OrgUnit
import uz.lebellion.org.repo.BranchRepository
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.web.CreateUnitRequest
import uz.lebellion.org.web.UnitResponse
import uz.lebellion.org.web.toResponse
import java.util.UUID

@Service
class UnitService(
    private val units: UnitRepository,
    private val branches: BranchRepository,
) {

    /**
     * Create a unit under a branch. FOUNDER may target any branch in the org; a BRANCH_MANAGER only
     * their own. A branch outside the caller's scope is reported as 404 (never revealing it exists).
     */
    @Transactional
    fun create(principal: AuthPrincipal, branchId: UUID, req: CreateUnitRequest): UnitResponse {
        requireFounderOrManager(principal)
        val scope = branchScopeOf(principal)
        if (scope != null && branchId != scope) throw NotFoundException("branch not found")
        val branch = branches.findByIdAndOrganizationId(branchId, principal.organizationId)
            ?: throw NotFoundException("branch not found")
        val unit = units.save(
            OrgUnit(
                organizationId = principal.organizationId,
                branchId = branch.id!!,
                name = req.name.trim(),
            ),
        )
        return unit.toResponse()
    }

    /**
     * List units: FOUNDER sees the whole org (optionally filtered by `branchId`); a BRANCH_MANAGER sees
     * only their own branch. A manager asking for a different branch gets an empty page (scope ∩ filter).
     */
    @Transactional(readOnly = true)
    fun list(principal: AuthPrincipal, limit: Int, cursor: String?, branchIdFilter: UUID?): Page<UnitResponse> {
        requireFounderOrManager(principal)
        val scope = branchScopeOf(principal)
        if (scope != null && branchIdFilter != null && scope != branchIdFilter) {
            return Page(emptyList(), null)
        }
        val effectiveBranch = scope ?: branchIdFilter
        val (afterCreatedAt, afterId) = cursorFloor(cursor)
        val pageable = PageRequest.ofSize(limit + 1)
        val rows = if (effectiveBranch != null) {
            units.pageInBranch(principal.organizationId, effectiveBranch, afterCreatedAt, afterId, pageable)
        } else {
            units.page(principal.organizationId, afterCreatedAt, afterId, pageable)
        }
        return pageOf(rows, limit, { it.createdAt }, { it.id!! }) { it.toResponse() }
    }
}
