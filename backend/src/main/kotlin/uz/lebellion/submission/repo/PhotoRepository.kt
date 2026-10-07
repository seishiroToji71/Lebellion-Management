package uz.lebellion.submission.repo

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import uz.lebellion.submission.domain.Photo
import java.time.Instant
import java.util.UUID

interface PhotoRepository : JpaRepository<Photo, UUID> {

    fun findByStorageKey(storageKey: String): Photo?

    /**
     * Exact SHA-256 probe within the (org, unit, item) scope and the 60-day window. Includes REJECTED
     * submissions on purpose: resending the identical file is always a duplicate, review state aside.
     */
    @Query(
        """
        select count(p) > 0 from Photo p
         where p.organizationId = :orgId and p.unitId = :unitId and p.itemId = :itemId
           and p.createdAt >= :cutoff and p.sha256 = :sha256
        """,
    )
    fun existsExact(
        @Param("orgId") orgId: UUID,
        @Param("unitId") unitId: UUID,
        @Param("itemId") itemId: UUID,
        @Param("sha256") sha256: String,
        @Param("cutoff") cutoff: Instant,
    ): Boolean

    /**
     * Candidate dHashes for near-duplicate comparison within the (org, unit, item) scope and the 60-day
     * window, EXCLUDING photos of REJECTED submissions (a legitimate re-shoot after a rejection must not
     * be treated as a duplicate). Hamming distance is computed in the service against the per-item threshold.
     */
    @Query(
        """
        select p.dhash from Photo p join Submission s on s.id = p.submissionId
         where p.organizationId = :orgId and p.unitId = :unitId and p.itemId = :itemId
           and p.createdAt >= :cutoff and s.status <> uz.lebellion.submission.domain.SubmissionStatus.REJECTED
        """,
    )
    fun nearDupCandidateHashes(
        @Param("orgId") orgId: UUID,
        @Param("unitId") unitId: UUID,
        @Param("itemId") itemId: UUID,
        @Param("cutoff") cutoff: Instant,
    ): List<Long>
}
