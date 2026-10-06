package uz.lebellion.checklist.repo

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import uz.lebellion.checklist.domain.CriterionLibrary
import java.time.Instant
import java.util.UUID

/** Keyset pagination over `(createdAt, id)`; see `BranchRepository` for the floor-cursor rationale. */
interface CriterionRepository : JpaRepository<CriterionLibrary, UUID> {

    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): CriterionLibrary?

    /** Batch-load (scoped) the criteria linked by a template's items, to resolve their text in one query. */
    fun findByOrganizationIdAndIdIn(organizationId: UUID, ids: Collection<UUID>): List<CriterionLibrary>

    @Query(
        """
        select c from CriterionLibrary c
         where c.organizationId = :orgId
           and (c.createdAt > :afterCreatedAt or (c.createdAt = :afterCreatedAt and c.id > :afterId))
         order by c.createdAt asc, c.id asc
        """,
    )
    fun page(
        @Param("orgId") orgId: UUID,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<CriterionLibrary>
}
