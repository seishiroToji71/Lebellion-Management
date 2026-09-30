package uz.lebellion.org.repo

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import uz.lebellion.org.domain.Branch
import java.time.Instant
import java.util.UUID

/**
 * Keyset (not offset) pagination over `(createdAt, id)`. The cursor boundary is always passed as
 * concrete values — the first page uses a floor (epoch / zero-UUID) rather than a NULL — so Postgres
 * can always infer the parameter types (a bare `:param is null` cannot be typed and fails at execution).
 * Callers request `Pageable` of size `limit + 1` to detect whether a further page exists.
 */
interface BranchRepository : JpaRepository<Branch, UUID> {

    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): Branch?

    @Query(
        """
        select b from Branch b
         where b.organizationId = :orgId
           and (b.createdAt > :afterCreatedAt or (b.createdAt = :afterCreatedAt and b.id > :afterId))
         order by b.createdAt asc, b.id asc
        """,
    )
    fun page(
        @Param("orgId") orgId: UUID,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<Branch>

    /** Same page, pinned to a single branch (BRANCH_MANAGER scope). */
    @Query(
        """
        select b from Branch b
         where b.organizationId = :orgId
           and b.id = :branchId
           and (b.createdAt > :afterCreatedAt or (b.createdAt = :afterCreatedAt and b.id > :afterId))
         order by b.createdAt asc, b.id asc
        """,
    )
    fun pageInBranch(
        @Param("orgId") orgId: UUID,
        @Param("branchId") branchId: UUID,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<Branch>
}
