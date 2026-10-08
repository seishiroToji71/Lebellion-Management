package uz.lebellion.review.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.review.domain.Review
import java.util.UUID

interface ReviewRepository : JpaRepository<Review, UUID> {
    fun findBySubmissionIdOrderByCreatedAtAscIdAsc(submissionId: UUID): List<Review>
}
