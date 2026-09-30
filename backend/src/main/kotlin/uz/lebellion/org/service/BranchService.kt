package uz.lebellion.org.service

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.ForbiddenException
import uz.lebellion.common.web.Page
import uz.lebellion.common.web.pageOf
import uz.lebellion.org.domain.Branch
import uz.lebellion.org.repo.BranchRepository
import uz.lebellion.org.web.BranchResponse
import uz.lebellion.org.web.CreateBranchRequest
import uz.lebellion.org.web.toResponse

@Service
class BranchService(private val branches: BranchRepository) {

    /** Create a branch. FOUNDER only; the branch is bound to the caller's organization. */
    @Transactional
    fun create(principal: AuthPrincipal, req: CreateBranchRequest): BranchResponse {
        if (principal.role != Role.FOUNDER) throw ForbiddenException("only a founder may create branches")
        val branch = branches.save(
            Branch(
                organizationId = principal.organizationId,
                name = req.name.trim(),
                address = req.address?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
        return branch.toResponse()
    }

    /** List branches: FOUNDER sees the whole org; BRANCH_MANAGER sees only their own branch. */
    @Transactional(readOnly = true)
    fun list(principal: AuthPrincipal, limit: Int, cursor: String?): Page<BranchResponse> {
        requireFounderOrManager(principal)
        val scope = branchScopeOf(principal)
        val (afterCreatedAt, afterId) = cursorFloor(cursor)
        val pageable = PageRequest.ofSize(limit + 1)
        val rows = if (scope != null) {
            branches.pageInBranch(principal.organizationId, scope, afterCreatedAt, afterId, pageable)
        } else {
            branches.page(principal.organizationId, afterCreatedAt, afterId, pageable)
        }
        return pageOf(rows, limit, { it.createdAt }, { it.id!! }) { it.toResponse() }
    }
}
