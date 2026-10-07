package uz.lebellion.submission.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.submission.domain.BlockedAttempt
import java.util.UUID

interface BlockedAttemptRepository : JpaRepository<BlockedAttempt, UUID>
