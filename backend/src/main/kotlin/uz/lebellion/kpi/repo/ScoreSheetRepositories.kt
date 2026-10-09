package uz.lebellion.kpi.repo

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import uz.lebellion.kpi.domain.KpiCriterion
import uz.lebellion.kpi.domain.ScoreBand
import uz.lebellion.kpi.domain.ScoreSheet
import uz.lebellion.kpi.domain.ScoreSheetItem
import java.util.UUID

interface KpiCriterionRepository : JpaRepository<KpiCriterion, UUID> {
    fun findByOrganizationIdAndCode(organizationId: UUID, code: String): KpiCriterion?
}

interface ScoreSheetRepository : JpaRepository<ScoreSheet, UUID> {
    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): ScoreSheet?
    fun findByOrganizationIdAndRoleKey(organizationId: UUID, roleKey: String): ScoreSheet?
    fun findByOrganizationIdOrderByRoleKeyAsc(organizationId: UUID): List<ScoreSheet>
}

interface ScoreSheetItemRepository : JpaRepository<ScoreSheetItem, UUID> {
    fun findByScoreSheetIdOrderBySortOrderAscIdAsc(scoreSheetId: UUID): List<ScoreSheetItem>

    /** Bulk delete for idempotent re-seed (executed immediately — before any re-insert). */
    @Modifying
    @Query("delete from ScoreSheetItem i where i.scoreSheetId = :scoreSheetId")
    fun deleteByScoreSheetId(@Param("scoreSheetId") scoreSheetId: UUID): Int
}

interface ScoreBandRepository : JpaRepository<ScoreBand, UUID> {
    fun findByScoreSheetIdOrderBySortOrderAscFromScoreAsc(scoreSheetId: UUID): List<ScoreBand>

    @Modifying
    @Query("delete from ScoreBand b where b.scoreSheetId = :scoreSheetId")
    fun deleteByScoreSheetId(@Param("scoreSheetId") scoreSheetId: UUID): Int
}
