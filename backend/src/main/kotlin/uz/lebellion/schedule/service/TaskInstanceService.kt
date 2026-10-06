package uz.lebellion.schedule.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.checklist.service.ensureUnitInScope
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.org.service.requireFounderOrManager
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.repo.TaskInstanceRepository
import uz.lebellion.schedule.web.TaskInstanceResponse
import uz.lebellion.schedule.web.toResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class TaskInstanceService(
    private val taskInstances: TaskInstanceRepository,
    private val units: UnitRepository,
    private val clock: Clock,
) {

    /**
     * A unit's upcoming task instances in `[from, to)` (defaults to the next 7 days). FOUNDER/BRANCH_MANAGER;
     * the employee-facing feed is a later (mobile) slice. Scope is enforced via the unit.
     */
    @Transactional(readOnly = true)
    fun list(principal: AuthPrincipal, unitId: UUID, from: Instant?, to: Instant?): List<TaskInstanceResponse> {
        requireFounderOrManager(principal)
        ensureUnitInScope(units, principal, unitId)
        val f = from ?: Instant.now(clock)
        val t = to ?: f.plus(Duration.ofDays(7))
        if (!t.isAfter(f)) throw RequestValidationException("'to' must be after 'from'")
        return taskInstances
            .findByOrganizationIdAndUnitIdAndStatusNotAndDueAtGreaterThanEqualAndDueAtLessThanOrderByDueAtAscIdAsc(
                principal.organizationId, unitId, TaskStatus.CANCELLED, f, t,
            )
            .map { it.toResponse() }
    }
}
