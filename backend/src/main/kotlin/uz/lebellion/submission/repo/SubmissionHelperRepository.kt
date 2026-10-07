package uz.lebellion.submission.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.submission.domain.SubmissionHelper
import java.util.UUID

interface SubmissionHelperRepository : JpaRepository<SubmissionHelper, UUID> {
    fun findBySubmissionIdOrderByCreatedAtAscIdAsc(submissionId: UUID): List<SubmissionHelper>
    fun findBySubmissionIdAndEmployeeId(submissionId: UUID, employeeId: UUID): SubmissionHelper?
}
