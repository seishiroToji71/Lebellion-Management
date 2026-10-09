package uz.lebellion.kpi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.kpi.domain.Evaluation
import uz.lebellion.kpi.domain.EvaluationItem
import uz.lebellion.kpi.domain.EvaluationStatus
import uz.lebellion.kpi.repo.EvaluationItemRepository
import uz.lebellion.kpi.repo.EvaluationRepository
import uz.lebellion.kpi.repo.KpiCriterionRepository
import uz.lebellion.kpi.repo.ScoreBandRepository
import uz.lebellion.kpi.repo.ScoreSheetItemRepository
import uz.lebellion.kpi.repo.ScoreSheetRepository
import uz.lebellion.kpi.web.CreateEvaluationRequest
import uz.lebellion.kpi.web.EvaluationItemView
import uz.lebellion.kpi.web.EvaluationResponse
import uz.lebellion.kpi.web.EvaluationFinalizedException
import uz.lebellion.kpi.web.InvalidAwardException
import uz.lebellion.kpi.web.RecommendationView
import uz.lebellion.kpi.web.SetAwardRequest
import uz.lebellion.review.service.ReviewPermissions
import uz.lebellion.review.web.CannotScoreOwnException
import java.math.BigDecimal
import java.util.UUID

/**
 * Monthly per-employee evaluation. Requires `can_score`; a reviewer may not evaluate themselves. Awards
 * are ∈ {0, points/2, points}. FINALIZE sums the awards, snapshots per-item max points, resolves the band
 * (final rounded half-up), freezes the row, and audits it. There are no auto-hints in v1 — the reviewer
 * enters each award.
 */
@Service
class EvaluationService(
    private val evaluations: EvaluationRepository,
    private val evaluationItems: EvaluationItemRepository,
    private val sheets: ScoreSheetRepository,
    private val sheetItems: ScoreSheetItemRepository,
    private val bands: ScoreBandRepository,
    private val criteria: KpiCriterionRepository,
    private val users: AppUserRepository,
    private val permissions: ReviewPermissions,
    private val audit: AuditLogRecorder,
) {

    @Transactional
    fun create(principal: AuthPrincipal, req: CreateEvaluationRequest): EvaluationResponse {
        permissions.requireCanScore(principal)
        if (req.employeeId == principal.userId) throw CannotScoreOwnException()
        sheets.findByIdAndOrganizationId(req.scoreSheetId, principal.organizationId)
            ?: throw NotFoundException("score sheet not found")
        users.findByIdAndOrganizationId(req.employeeId, principal.organizationId)
            ?: throw NotFoundException("employee not found")
        val month = req.periodMonth.withDayOfMonth(1)
        val existing = evaluations.findByOrganizationIdAndEmployeeIdAndScoreSheetIdAndPeriodMonth(
            principal.organizationId, req.employeeId, req.scoreSheetId, month,
        )
        val eval = existing ?: evaluations.save(
            Evaluation(
                organizationId = principal.organizationId,
                employeeId = req.employeeId,
                scoreSheetId = req.scoreSheetId,
                periodMonth = month,
                reviewerUserId = principal.userId,
            ),
        )
        return buildView(eval)
    }

    @Transactional
    fun setAward(principal: AuthPrincipal, evaluationId: UUID, req: SetAwardRequest): EvaluationResponse {
        val eval = requireDraft(principal, evaluationId)
        val item = sheetItems.findById(req.scoreSheetItemId).orElse(null)
        if (item == null || item.organizationId != principal.organizationId || item.scoreSheetId != eval.scoreSheetId) {
            throw NotFoundException("score sheet item not found")
        }
        requireValidAward(req.awardedPoints, item.points)
        val existing = evaluationItems.findByEvaluationIdAndScoreSheetItemId(eval.id!!, item.id!!)
        if (existing != null) {
            existing.awardedPoints = req.awardedPoints
            evaluationItems.save(existing)
        } else {
            evaluationItems.save(EvaluationItem(principal.organizationId, eval.id!!, item.id!!, req.awardedPoints))
        }
        return buildView(eval)
    }

    @Transactional
    fun finalize(principal: AuthPrincipal, evaluationId: UUID): EvaluationResponse {
        val eval = requireDraft(principal, evaluationId)
        val itemsById = sheetItems.findByScoreSheetIdOrderBySortOrderAscIdAsc(eval.scoreSheetId).associateBy { it.id }
        val evalItems = evaluationItems.findByEvaluationIdOrderByIdAsc(eval.id!!)
        var sum = BigDecimal.ZERO
        for (ei in evalItems) {
            sum = sum.add(ei.awardedPoints)
            ei.maxPoints = itemsById[ei.scoreSheetItemId]?.points
            evaluationItems.save(ei)
        }
        val rounded = ScoreSheetValidation.roundHalfUp(sum)
        val band = bands.findByScoreSheetIdOrderBySortOrderAscFromScoreAsc(eval.scoreSheetId).firstOrNull { it.contains(rounded) }
        eval.finalScore = sum.setScale(1)
        eval.bandLabel = band?.label
        eval.bandPercent = band?.percent
        eval.status = EvaluationStatus.FINALIZED
        evaluations.save(eval)
        audit.record(
            organizationId = principal.organizationId,
            eventType = "EVALUATION_FINALIZED",
            actorUserId = principal.userId,
            targetType = "EVALUATION",
            targetId = eval.id,
            metadata = mapOf(
                "employeeId" to eval.employeeId.toString(),
                "scoreSheetId" to eval.scoreSheetId.toString(),
                "finalScore" to eval.finalScore.toString(),
                "band" to band?.label?.name,
                "percent" to band?.percent,
            ),
        )
        return buildView(eval)
    }

    @Transactional(readOnly = true)
    fun get(principal: AuthPrincipal, evaluationId: UUID): EvaluationResponse {
        permissions.requireCanScore(principal)
        val eval = evaluations.findByIdAndOrganizationId(evaluationId, principal.organizationId)
            ?: throw NotFoundException("evaluation not found")
        return buildView(eval)
    }

    private fun requireDraft(principal: AuthPrincipal, evaluationId: UUID): Evaluation {
        val eval = evaluations.findByIdAndOrganizationId(evaluationId, principal.organizationId)
            ?: throw NotFoundException("evaluation not found")
        if (eval.status == EvaluationStatus.FINALIZED) throw EvaluationFinalizedException()
        permissions.requireCanScore(principal)
        if (eval.employeeId == principal.userId) throw CannotScoreOwnException()
        return eval
    }

    /** Allowed per-item awards are exactly {0, points/2, points}. */
    private fun requireValidAward(awarded: BigDecimal, points: Int) {
        val allowed = listOf(BigDecimal.ZERO, BigDecimal(points).divide(BigDecimal(2)), BigDecimal(points))
        if (allowed.none { it.compareTo(awarded) == 0 }) {
            throw InvalidAwardException("award must be one of {0, ${points.toDouble() / 2}, $points}")
        }
    }

    private fun buildView(eval: Evaluation): EvaluationResponse {
        val sheetItemList = sheetItems.findByScoreSheetIdOrderBySortOrderAscIdAsc(eval.scoreSheetId)
        val critById = criteria.findAllById(sheetItemList.map { it.kpiCriterionId }).associateBy { it.id }
        val awardsByItem = evaluationItems.findByEvaluationIdOrderByIdAsc(eval.id!!).associateBy { it.scoreSheetItemId }
        val finalized = eval.status == EvaluationStatus.FINALIZED

        val itemViews = sheetItemList.map { si ->
            val award = awardsByItem[si.id]
            val points = if (finalized) (award?.maxPoints ?: si.points) else si.points
            EvaluationItemView(
                scoreSheetItemId = si.id!!,
                criterionCode = critById[si.kpiCriterionId]?.code ?: "",
                titleRu = critById[si.kpiCriterionId]?.titleRu ?: "",
                points = points,
                awardedPoints = award?.awardedPoints,
            )
        }

        val finalScore = if (finalized) {
            eval.finalScore ?: BigDecimal.ZERO.setScale(1)
        } else {
            awardsByItem.values.fold(BigDecimal.ZERO) { acc, ai -> acc.add(ai.awardedPoints) }.setScale(1)
        }

        val recommendation: RecommendationView? = if (finalized) {
            eval.bandLabel?.let { RecommendationView(it, eval.bandPercent) }
        } else {
            val rounded = ScoreSheetValidation.roundHalfUp(finalScore)
            bands.findByScoreSheetIdOrderBySortOrderAscFromScoreAsc(eval.scoreSheetId)
                .firstOrNull { it.contains(rounded) }
                ?.let { RecommendationView(it.label, it.percent) }
        }

        return EvaluationResponse(
            id = eval.id!!,
            employeeId = eval.employeeId,
            scoreSheetId = eval.scoreSheetId,
            periodMonth = eval.periodMonth,
            status = eval.status,
            reviewerUserId = eval.reviewerUserId,
            finalScore = finalScore,
            recommendation = recommendation,
            items = itemViews,
        )
    }
}
