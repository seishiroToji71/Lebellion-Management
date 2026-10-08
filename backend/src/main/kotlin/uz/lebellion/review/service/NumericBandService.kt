package uz.lebellion.review.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.ForbiddenException
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.checklist.domain.ItemType
import uz.lebellion.checklist.repo.ChecklistItemRepository
import uz.lebellion.review.domain.NumericBand
import uz.lebellion.review.repo.NumericBandRepository
import uz.lebellion.review.web.CreateNumericBandRequest
import uz.lebellion.review.web.NotScorableException
import uz.lebellion.review.web.NumericBandResponse
import java.util.UUID

/** Manages the scoring bands of a NUMERIC item. FOUNDER-only configuration (org-wide scoring policy). */
@Service
class NumericBandService(
    private val bands: NumericBandRepository,
    private val checklistItems: ChecklistItemRepository,
) {

    @Transactional
    fun create(principal: AuthPrincipal, itemId: UUID, req: CreateNumericBandRequest): NumericBandResponse {
        if (principal.role != Role.FOUNDER) throw ForbiddenException("only a founder may configure scoring bands")
        val item = checklistItems.findByIdAndOrganizationId(itemId, principal.organizationId)
            ?: throw NotFoundException("item not found")
        if (item.type != ItemType.NUMERIC) throw NotScorableException("numeric bands require a NUMERIC item")
        if (req.lowerBound != null && req.upperBound != null && req.lowerBound >= req.upperBound) {
            throw NotScorableException("lowerBound must be < upperBound")
        }
        val band = bands.save(
            NumericBand(
                organizationId = principal.organizationId,
                itemId = itemId,
                lowerBound = req.lowerBound,
                upperBound = req.upperBound,
                points = req.points,
                label = req.label?.trim()?.takeIf { it.isNotEmpty() },
                sortOrder = req.sortOrder,
            ),
        )
        return band.toResponse()
    }

    @Transactional(readOnly = true)
    fun list(principal: AuthPrincipal, itemId: UUID): List<NumericBandResponse> {
        if (principal.role != Role.FOUNDER && principal.role != Role.BRANCH_MANAGER) {
            throw ForbiddenException("not permitted")
        }
        checklistItems.findByIdAndOrganizationId(itemId, principal.organizationId)
            ?: throw NotFoundException("item not found")
        return bands.findByOrganizationIdAndItemIdOrderBySortOrderAscIdAsc(principal.organizationId, itemId).map { it.toResponse() }
    }

    private fun NumericBand.toResponse() =
        NumericBandResponse(id!!, itemId, lowerBound, upperBound, points, label, sortOrder)
}
