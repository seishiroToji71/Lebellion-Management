package uz.lebellion.review.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.review.domain.TaskScore
import java.util.UUID

interface TaskScoreRepository : JpaRepository<TaskScore, UUID> {
    fun findByTaskInstanceId(taskInstanceId: UUID): TaskScore?
}
