package uz.lebellion.submission.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.submission.domain.Submission
import java.util.UUID

interface SubmissionRepository : JpaRepository<Submission, UUID> {
    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): Submission?
}
