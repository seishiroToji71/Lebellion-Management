package uz.lebellion.submission.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.submission.repo.SubmissionHelperRepository
import uz.lebellion.submission.repo.SubmissionRepository
import uz.lebellion.submission.web.HelperRef
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Confirmation of a helper tag. Only the TAGGED employee may confirm their own participation: the row is
 * looked up by (submission, principal.userId), so a caller who was not tagged gets a 404 (no leak). The
 * confirmation instant is server time.
 */
@Service
class SubmissionHelperService(
    private val submissions: SubmissionRepository,
    private val helpers: SubmissionHelperRepository,
    private val clock: Clock,
) {
    @Transactional
    fun confirm(principal: AuthPrincipal, submissionId: UUID): HelperRef {
        submissions.findByIdAndOrganizationId(submissionId, principal.organizationId)
            ?: throw NotFoundException("submission not found")
        val row = helpers.findBySubmissionIdAndEmployeeId(submissionId, principal.userId)
            ?: throw NotFoundException("not a tagged helper on this submission")
        if (row.confirmedAt == null) {
            row.confirmedAt = Instant.now(clock)
            helpers.save(row)
        }
        return HelperRef(row.employeeId, row.confirmedAt)
    }
}
