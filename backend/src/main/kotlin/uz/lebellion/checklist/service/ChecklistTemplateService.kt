package uz.lebellion.checklist.service

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.checklist.domain.ChecklistTemplate
import uz.lebellion.checklist.repo.ChecklistTemplateRepository
import uz.lebellion.checklist.web.ChecklistTemplateResponse
import uz.lebellion.checklist.web.CreateChecklistTemplateRequest
import uz.lebellion.checklist.web.toResponse
import uz.lebellion.common.web.Page
import uz.lebellion.common.web.pageOf
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.service.cursorFloor
import uz.lebellion.org.service.requireFounderOrManager
import java.util.UUID

@Service
class ChecklistTemplateService(
    private val templates: ChecklistTemplateRepository,
    private val units: UnitRepository,
) {

    /**
     * Create a template under a unit. FOUNDER may target any unit of the org; a BRANCH_MANAGER only units
     * of their own branch. Several templates per unit are allowed (one per position).
     */
    @Transactional
    fun create(principal: AuthPrincipal, unitId: UUID, req: CreateChecklistTemplateRequest): ChecklistTemplateResponse {
        requireFounderOrManager(principal)
        ensureUnitInScope(units, principal, unitId)
        val template = templates.save(
            ChecklistTemplate(
                organizationId = principal.organizationId,
                unitId = unitId,
                name = req.name.trim(),
            ),
        )
        return template.toResponse()
    }

    /** List a unit's templates (keyset paginated), same role/branch scoping as [create]. */
    @Transactional(readOnly = true)
    fun list(principal: AuthPrincipal, unitId: UUID, limit: Int, cursor: String?): Page<ChecklistTemplateResponse> {
        requireFounderOrManager(principal)
        ensureUnitInScope(units, principal, unitId)
        val (afterCreatedAt, afterId) = cursorFloor(cursor)
        val rows = templates.pageInUnit(principal.organizationId, unitId, afterCreatedAt, afterId, PageRequest.ofSize(limit + 1))
        return pageOf(rows, limit, { it.createdAt }, { it.id!! }) { it.toResponse() }
    }
}
