package uz.lebellion.checklist.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.checklist.domain.ChecklistItem
import uz.lebellion.checklist.domain.CriterionLibrary
import uz.lebellion.checklist.repo.ChecklistItemRepository
import uz.lebellion.checklist.repo.ChecklistTemplateRepository
import uz.lebellion.checklist.repo.CriterionRepository
import uz.lebellion.checklist.web.ChecklistItemResponse
import uz.lebellion.checklist.web.CreateChecklistItemRequest
import uz.lebellion.checklist.web.toResponse
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.service.requireFounderOrManager
import java.util.UUID

@Service
class ChecklistItemService(
    private val items: ChecklistItemRepository,
    private val templates: ChecklistTemplateRepository,
    private val criteria: CriterionRepository,
    private val units: UnitRepository,
) {

    /**
     * Add an item to a template. A `criterionId` links a library criterion (type + text inherited, scalar
     * config seeded from the criterion's defaults and overridable); otherwise the item is ad-hoc and must
     * carry its own `type`, `titleRu`, `titleUz`. Scope is checked via the template's unit.
     */
    @Transactional
    fun create(principal: AuthPrincipal, templateId: UUID, req: CreateChecklistItemRequest): ChecklistItemResponse {
        requireFounderOrManager(principal)
        val template = templates.findByIdAndOrganizationId(templateId, principal.organizationId)
            ?: throw NotFoundException("template not found")
        ensureUnitInScope(units, principal, template.unitId)

        val criterion: CriterionLibrary?
        val item: ChecklistItem
        if (req.criterionId != null) {
            criterion = criteria.findByIdAndOrganizationId(req.criterionId, principal.organizationId)
                ?: throw NotFoundException("criterion not found")
            item = ChecklistItem(
                organizationId = principal.organizationId,
                templateId = templateId,
                criterionId = criterion.id,
                type = criterion.type,
                photoRequired = req.photoRequired ?: criterion.defaultPhotoRequired,
                points = req.points ?: criterion.defaultPoints,
                critical = req.critical ?: criterion.defaultCritical,
                staticScene = req.staticScene ?: criterion.defaultStaticScene,
                dhashThreshold = req.dhashThreshold ?: criterion.defaultDhashThreshold,
                sortOrder = req.sortOrder,
            )
        } else {
            criterion = null
            val type = req.type
                ?: throw RequestValidationException("type is required for an ad-hoc item")
            val titleRu = req.titleRu?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw RequestValidationException("titleRu is required for an ad-hoc item")
            val titleUz = req.titleUz?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw RequestValidationException("titleUz is required for an ad-hoc item")
            item = ChecklistItem(
                organizationId = principal.organizationId,
                templateId = templateId,
                criterionId = null,
                type = type,
                titleRu = titleRu,
                titleUz = titleUz,
                standardRu = req.standardRu?.trim()?.takeIf { it.isNotEmpty() },
                standardUz = req.standardUz?.trim()?.takeIf { it.isNotEmpty() },
                originalText = req.originalText?.trim()?.takeIf { it.isNotEmpty() },
                photoRequired = req.photoRequired ?: false,
                points = req.points ?: 0,
                critical = req.critical ?: false,
                staticScene = req.staticScene ?: false,
                dhashThreshold = req.dhashThreshold ?: 6,
                sortOrder = req.sortOrder,
            )
        }
        return items.save(item).toResponse(criterion)
    }

    /** List a template's items, ordered by `(sortOrder, id)`, resolving linked-criterion text in one query. */
    @Transactional(readOnly = true)
    fun list(principal: AuthPrincipal, templateId: UUID): List<ChecklistItemResponse> {
        requireFounderOrManager(principal)
        val template = templates.findByIdAndOrganizationId(templateId, principal.organizationId)
            ?: throw NotFoundException("template not found")
        ensureUnitInScope(units, principal, template.unitId)

        val rows = items.findByOrganizationIdAndTemplateIdOrderBySortOrderAscIdAsc(principal.organizationId, templateId)
        val criterionIds = rows.mapNotNull { it.criterionId }.distinct()
        val byId = if (criterionIds.isEmpty()) {
            emptyMap()
        } else {
            criteria.findByOrganizationIdAndIdIn(principal.organizationId, criterionIds).associateBy { it.id!! }
        }
        return rows.map { it.toResponse(it.criterionId?.let(byId::get)) }
    }
}
