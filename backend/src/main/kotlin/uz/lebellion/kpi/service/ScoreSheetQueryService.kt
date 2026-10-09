package uz.lebellion.kpi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.kpi.repo.KpiCriterionRepository
import uz.lebellion.kpi.repo.ScoreBandRepository
import uz.lebellion.kpi.repo.ScoreSheetItemRepository
import uz.lebellion.kpi.repo.ScoreSheetRepository
import uz.lebellion.kpi.web.ScoreBandView
import uz.lebellion.kpi.web.ScoreSheetItemView
import uz.lebellion.kpi.web.ScoreSheetResponse
import uz.lebellion.kpi.web.ScoreSheetSummary
import uz.lebellion.org.service.requireFounderOrManager
import java.util.UUID

/** Read access to the seeded score sheets (role config). FOUNDER / BRANCH_MANAGER. */
@Service
class ScoreSheetQueryService(
    private val sheets: ScoreSheetRepository,
    private val items: ScoreSheetItemRepository,
    private val bands: ScoreBandRepository,
    private val criteria: KpiCriterionRepository,
) {

    @Transactional(readOnly = true)
    fun list(principal: AuthPrincipal): List<ScoreSheetSummary> {
        requireFounderOrManager(principal)
        return sheets.findByOrganizationIdOrderByRoleKeyAsc(principal.organizationId)
            .map { ScoreSheetSummary(it.id!!, it.roleKey, it.nameRu, it.nameUz, it.active) }
    }

    @Transactional(readOnly = true)
    fun get(principal: AuthPrincipal, sheetId: UUID): ScoreSheetResponse {
        requireFounderOrManager(principal)
        val sheet = sheets.findByIdAndOrganizationId(sheetId, principal.organizationId)
            ?: throw NotFoundException("score sheet not found")
        val sheetItems = items.findByScoreSheetIdOrderBySortOrderAscIdAsc(sheet.id!!)
        val critById = criteria.findAllById(sheetItems.map { it.kpiCriterionId }).associateBy { it.id }
        val itemViews = sheetItems.map { si ->
            val c = critById[si.kpiCriterionId]
            ScoreSheetItemView(
                id = si.id!!,
                criterionCode = c?.code ?: "",
                type = c!!.type,
                titleRu = c.titleRu,
                titleUz = c.titleUz,
                points = si.points,
                direction = si.direction,
                sortOrder = si.sortOrder,
            )
        }
        val bandViews = bands.findByScoreSheetIdOrderBySortOrderAscFromScoreAsc(sheet.id!!)
            .map { ScoreBandView(it.fromScore, it.toScore, it.label, it.percent) }
        return ScoreSheetResponse(
            id = sheet.id!!,
            roleKey = sheet.roleKey,
            nameRu = sheet.nameRu,
            nameUz = sheet.nameUz,
            positionSuggestion = sheet.positionSuggestion,
            active = sheet.active,
            totalPoints = sheetItems.sumOf { it.points },
            items = itemViews,
            bands = bandViews,
        )
    }
}
