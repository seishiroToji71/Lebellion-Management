package uz.lebellion.checklist.service

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.ForbiddenException
import uz.lebellion.checklist.domain.CriterionLibrary
import uz.lebellion.checklist.repo.CriterionRepository
import uz.lebellion.checklist.web.CreateCriterionRequest
import uz.lebellion.checklist.web.CriterionResponse
import uz.lebellion.checklist.web.toResponse
import uz.lebellion.common.web.Page
import uz.lebellion.common.web.pageOf
import uz.lebellion.org.service.cursorFloor
import uz.lebellion.org.service.requireFounderOrManager

@Service
class CriterionService(private val criteria: CriterionRepository) {

    /** Create a library criterion. FOUNDER only — the library is org-wide shared configuration. */
    @Transactional
    fun create(principal: AuthPrincipal, req: CreateCriterionRequest): CriterionResponse {
        if (principal.role != Role.FOUNDER) throw ForbiddenException("only a founder may manage the criterion library")
        val criterion = criteria.save(
            CriterionLibrary(
                organizationId = principal.organizationId,
                type = req.type,
                titleRu = req.titleRu.trim(),
                titleUz = req.titleUz.trim(),
                standardRu = req.standardRu?.trim()?.takeIf { it.isNotEmpty() },
                standardUz = req.standardUz?.trim()?.takeIf { it.isNotEmpty() },
                originalText = req.originalText?.trim()?.takeIf { it.isNotEmpty() },
                defaultPhotoRequired = req.photoRequired,
                defaultPoints = req.points,
                defaultCritical = req.critical,
                defaultStaticScene = req.staticScene,
                defaultDhashThreshold = req.dhashThreshold,
            ),
        )
        return criterion.toResponse()
    }

    /** List criteria (whole org). FOUNDER and BRANCH_MANAGER may read, to compose templates from them. */
    @Transactional(readOnly = true)
    fun list(principal: AuthPrincipal, limit: Int, cursor: String?): Page<CriterionResponse> {
        requireFounderOrManager(principal)
        val (afterCreatedAt, afterId) = cursorFloor(cursor)
        val rows = criteria.page(principal.organizationId, afterCreatedAt, afterId, PageRequest.ofSize(limit + 1))
        return pageOf(rows, limit, { it.createdAt }, { it.id!! }) { it.toResponse() }
    }
}
