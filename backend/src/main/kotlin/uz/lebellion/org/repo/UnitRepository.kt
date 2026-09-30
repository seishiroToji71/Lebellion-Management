package uz.lebellion.org.repo

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import uz.lebellion.org.domain.OrgUnit
import java.time.Instant
import java.util.UUID

/** Keyset pagination over `(createdAt, id)`; see [BranchRepository] for the floor-cursor rationale. */
interface UnitRepository : JpaRepository<OrgUnit, UUID> {

    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): OrgUnit?

    @Query(
        """
        select u from OrgUnit u
         where u.organizationId = :orgId
           and (u.createdAt > :afterCreatedAt or (u.createdAt = :afterCreatedAt and u.id > :afterId))
         order by u.createdAt asc, u.id asc
        """,
    )
    fun page(
        @Param("orgId") orgId: UUID,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<OrgUnit>

    /** Same page, restricted to one branch (a `branchId` filter and/or the BRANCH_MANAGER scope). */
    @Query(
        """
        select u from OrgUnit u
         where u.organizationId = :orgId
           and u.branchId = :branchId
           and (u.createdAt > :afterCreatedAt or (u.createdAt = :afterCreatedAt and u.id > :afterId))
         order by u.createdAt asc, u.id asc
        """,
    )
    fun pageInBranch(
        @Param("orgId") orgId: UUID,
        @Param("branchId") branchId: UUID,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<OrgUnit>
}
