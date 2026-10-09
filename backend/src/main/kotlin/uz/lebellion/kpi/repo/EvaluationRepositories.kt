package uz.lebellion.kpi.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.kpi.domain.Evaluation
import uz.lebellion.kpi.domain.EvaluationItem
import java.time.LocalDate
import java.util.UUID

interface EvaluationRepository : JpaRepository<Evaluation, UUID> {
    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): Evaluation?
    fun findByOrganizationIdAndEmployeeIdAndScoreSheetIdAndPeriodMonth(
        organizationId: UUID,
        employeeId: UUID,
        scoreSheetId: UUID,
        periodMonth: LocalDate,
    ): Evaluation?
}

interface EvaluationItemRepository : JpaRepository<EvaluationItem, UUID> {
    fun findByEvaluationIdOrderByIdAsc(evaluationId: UUID): List<EvaluationItem>
    fun findByEvaluationIdAndScoreSheetItemId(evaluationId: UUID, scoreSheetItemId: UUID): EvaluationItem?
}
