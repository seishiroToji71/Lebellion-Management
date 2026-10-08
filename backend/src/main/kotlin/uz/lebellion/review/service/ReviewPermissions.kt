package uz.lebellion.review.service

import org.springframework.stereotype.Component
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.ForbiddenException
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.service.UnitLeadService
import java.util.UUID

/**
 * Shared review/score authorisation.
 * - visibility: FOUNDER any unit; BRANCH_MANAGER own branch; EMPLOYEE own unit (else 404, no leak).
 * - can_review: FOUNDER, or the unit's effective lead, or an `app_user.can_review` holder.
 * - can_score: FOUNDER, or an `app_user.can_score` holder.
 */
@Component
class ReviewPermissions(
    private val users: AppUserRepository,
    private val units: UnitRepository,
    private val unitLead: UnitLeadService,
) {

    fun requireUnitVisible(principal: AuthPrincipal, unitId: UUID) {
        when (principal.role) {
            Role.FOUNDER -> return
            Role.BRANCH_MANAGER -> {
                val unit = units.findByIdAndOrganizationId(unitId, principal.organizationId)
                if (unit == null || unit.branchId != principal.branchId) throw NotFoundException("not found")
            }
            Role.EMPLOYEE -> {
                val me = users.findByIdAndOrganizationId(principal.userId, principal.organizationId)
                if (me == null || me.unitId != unitId) throw NotFoundException("not found")
            }
        }
    }

    fun requireCanReview(principal: AuthPrincipal, unitId: UUID) {
        if (principal.role == Role.FOUNDER) return
        if (unitLead.effectiveLeadUserId(principal.organizationId, unitId) == principal.userId) return
        val me = users.findByIdAndOrganizationId(principal.userId, principal.organizationId)
        if (me?.canReview == true) return
        throw ForbiddenException("not permitted to review")
    }

    fun requireCanScore(principal: AuthPrincipal) {
        if (principal.role == Role.FOUNDER) return
        val me = users.findByIdAndOrganizationId(principal.userId, principal.organizationId)
        if (me?.canScore == true) return
        throw ForbiddenException("not permitted to score")
    }
}
