package uz.lebellion.auth.repo

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.domain.Invite
import uz.lebellion.auth.domain.InviteStatus
import uz.lebellion.auth.domain.Organization
import uz.lebellion.auth.domain.RefreshToken
import uz.lebellion.auth.domain.Role
import java.time.Instant
import java.util.UUID

interface OrganizationRepository : JpaRepository<Organization, UUID> {
    fun existsByNameIgnoreCase(name: String): Boolean
}

interface AppUserRepository : JpaRepository<AppUser, UUID> {
    fun findByEmailIgnoreCase(email: String): AppUser?
    fun findByPhone(phone: String): AppUser?
    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): AppUser?
    fun existsByEmailIgnoreCase(email: String): Boolean
    fun existsByPhone(phone: String): Boolean

    /** First active BRANCH_MANAGER of a branch — the fallback unit lead when none is assigned. */
    fun findFirstByOrganizationIdAndBranchIdAndRoleAndIsActiveTrueOrderByCreatedAtAsc(
        organizationId: UUID,
        branchId: UUID,
        role: Role,
    ): AppUser?

    /** Locks the user row so deactivate / recovery-invite serialize against concurrent writes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id and u.organizationId = :orgId")
    fun findByIdAndOrganizationIdForUpdate(@Param("id") id: UUID, @Param("orgId") orgId: UUID): AppUser?

    /**
     * Keyset page of users over (createdAt, id). Branch is resolved as `coalesce(unit.branch, user.branch)`
     * so EMPLOYEEs (whose branch lives on their unit) scope correctly. Every filter is a typed flag+value
     * pair — never a bare `:param is null`, which Postgres cannot type (see BranchRepository). Pass a
     * `*By* = false` with a throwaway value to disable a dimension; the manager scope reuses the same
     * resolved-branch expression.
     */
    @Query(
        """
        select u from AppUser u left join OrgUnit unit on unit.id = u.unitId
         where u.organizationId = :orgId
           and (:scoped = false or coalesce(unit.branchId, u.branchId) = :scopeBranch)
           and (:filterByBranch = false or coalesce(unit.branchId, u.branchId) = :branchFilter)
           and (:filterByUnit = false or u.unitId = :unitFilter)
           and (:filterByActive = false or u.isActive = :activeValue)
           and (u.createdAt > :afterCreatedAt or (u.createdAt = :afterCreatedAt and u.id > :afterId))
         order by u.createdAt asc, u.id asc
        """,
    )
    fun pageEmployees(
        @Param("orgId") orgId: UUID,
        @Param("scoped") scoped: Boolean,
        @Param("scopeBranch") scopeBranch: UUID,
        @Param("filterByBranch") filterByBranch: Boolean,
        @Param("branchFilter") branchFilter: UUID,
        @Param("filterByUnit") filterByUnit: Boolean,
        @Param("unitFilter") unitFilter: UUID,
        @Param("filterByActive") filterByActive: Boolean,
        @Param("activeValue") activeValue: Boolean,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<AppUser>
}

/**
 * Projection for the invite list. `branchId` is resolved as `coalesce(unit.branch, invite.branch)`
 * so EMPLOYEE invites (which carry a unit, never a branch) still expose a branch; recovery invites
 * are filtered out upstream, so a resolved row always has a branch.
 */
data class InviteListRow(
    val id: UUID,
    val role: Role,
    val branchId: UUID?,
    val unitId: UUID?,
    val status: InviteStatus,
    val createdBy: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
)

interface InviteRepository : JpaRepository<Invite, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invite i where i.codeHmac = :hmac")
    fun findByCodeHmacForUpdate(@Param("hmac") hmac: String): Invite?

    /** Locks the invite row so reissue/revoke serialize against a concurrent join (locks the same row). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invite i where i.id = :id and i.organizationId = :orgId")
    fun findByIdAndOrganizationIdForUpdate(@Param("id") id: UUID, @Param("orgId") orgId: UUID): Invite?

    // Keyset pagination over (createdAt, id); see BranchRepository for the floor-cursor rationale.
    // Recovery invites (targetEmployeeId set) are excluded — they have no branch and belong to the
    // employees slice. The expiresAt window is always applied with concrete bounds: PENDING uses
    // (now, +inf], EXPIRED uses (epoch, now], every other filter uses the full (epoch, +inf] range,
    // so the status/EXPIRED split needs no nullable parameter.

    @Query(
        """
        select new uz.lebellion.auth.repo.InviteListRow(
            i.id, i.role, coalesce(u.branchId, i.branchId), i.unitId,
            i.status, i.createdBy, i.createdAt, i.expiresAt, i.usedAt)
          from Invite i left join OrgUnit u on u.id = i.unitId
         where i.organizationId = :orgId
           and i.targetEmployeeId is null
           and i.expiresAt > :expiresAfter and i.expiresAt <= :expiresBefore
           and (i.createdAt > :afterCreatedAt or (i.createdAt = :afterCreatedAt and i.id > :afterId))
         order by i.createdAt asc, i.id asc
        """,
    )
    fun pageList(
        @Param("orgId") orgId: UUID,
        @Param("expiresAfter") expiresAfter: Instant,
        @Param("expiresBefore") expiresBefore: Instant,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<InviteListRow>

    @Query(
        """
        select new uz.lebellion.auth.repo.InviteListRow(
            i.id, i.role, coalesce(u.branchId, i.branchId), i.unitId,
            i.status, i.createdBy, i.createdAt, i.expiresAt, i.usedAt)
          from Invite i left join OrgUnit u on u.id = i.unitId
         where i.organizationId = :orgId
           and i.targetEmployeeId is null
           and i.status = :status
           and i.expiresAt > :expiresAfter and i.expiresAt <= :expiresBefore
           and (i.createdAt > :afterCreatedAt or (i.createdAt = :afterCreatedAt and i.id > :afterId))
         order by i.createdAt asc, i.id asc
        """,
    )
    fun pageListByStatus(
        @Param("orgId") orgId: UUID,
        @Param("status") status: InviteStatus,
        @Param("expiresAfter") expiresAfter: Instant,
        @Param("expiresBefore") expiresBefore: Instant,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<InviteListRow>

    /** Manager scope: `u.branchId = :scope` requires a unit, so only EMPLOYEE invites in that branch show. */
    @Query(
        """
        select new uz.lebellion.auth.repo.InviteListRow(
            i.id, i.role, u.branchId, i.unitId,
            i.status, i.createdBy, i.createdAt, i.expiresAt, i.usedAt)
          from Invite i join OrgUnit u on u.id = i.unitId
         where i.organizationId = :orgId
           and u.branchId = :scope
           and i.targetEmployeeId is null
           and i.expiresAt > :expiresAfter and i.expiresAt <= :expiresBefore
           and (i.createdAt > :afterCreatedAt or (i.createdAt = :afterCreatedAt and i.id > :afterId))
         order by i.createdAt asc, i.id asc
        """,
    )
    fun pageListInBranch(
        @Param("orgId") orgId: UUID,
        @Param("scope") scope: UUID,
        @Param("expiresAfter") expiresAfter: Instant,
        @Param("expiresBefore") expiresBefore: Instant,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<InviteListRow>

    @Query(
        """
        select new uz.lebellion.auth.repo.InviteListRow(
            i.id, i.role, u.branchId, i.unitId,
            i.status, i.createdBy, i.createdAt, i.expiresAt, i.usedAt)
          from Invite i join OrgUnit u on u.id = i.unitId
         where i.organizationId = :orgId
           and u.branchId = :scope
           and i.targetEmployeeId is null
           and i.status = :status
           and i.expiresAt > :expiresAfter and i.expiresAt <= :expiresBefore
           and (i.createdAt > :afterCreatedAt or (i.createdAt = :afterCreatedAt and i.id > :afterId))
         order by i.createdAt asc, i.id asc
        """,
    )
    fun pageListInBranchByStatus(
        @Param("orgId") orgId: UUID,
        @Param("scope") scope: UUID,
        @Param("status") status: InviteStatus,
        @Param("expiresAfter") expiresAfter: Instant,
        @Param("expiresBefore") expiresBefore: Instant,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<InviteListRow>
}

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findByTokenHash(tokenHash: String): RefreshToken?

    fun existsByUserIdAndDeviceId(userId: UUID, deviceId: String): Boolean

    fun findByUserId(userId: UUID): List<RefreshToken>

    /** Locks the presented token's row so concurrent refreshes with the same token serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.tokenHash = :tokenHash")
    fun findByTokenHashForUpdate(@Param("tokenHash") tokenHash: String): RefreshToken?

    /** The single live (not rotated, not revoked) token of a family — used by the grace path. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.familyId = :familyId and r.rotatedAt is null and r.revokedAt is null")
    fun findLiveByFamilyForUpdate(@Param("familyId") familyId: UUID): RefreshToken?

    /**
     * Revokes every not-yet-revoked token of a family (live + rotated ancestors). Returns the count,
     * so callers can tell a real kill (theft) from a no-op on an already-dead family (benign replay).
     */
    @Modifying
    @Query(
        """
        update RefreshToken r
           set r.revokedAt = :now, r.revokedReason = :reason
         where r.familyId = :familyId and r.revokedAt is null
        """,
    )
    fun revokeAllActiveForFamily(
        @Param("familyId") familyId: UUID,
        @Param("now") now: Instant,
        @Param("reason") reason: String,
    ): Int

    @Modifying
    @Query(
        """
        update RefreshToken r
           set r.revokedAt = :now, r.revokedReason = :reason
         where r.userId = :userId and r.revokedAt is null
        """,
    )
    fun revokeAllActiveForUser(
        @Param("userId") userId: UUID,
        @Param("now") now: Instant,
        @Param("reason") reason: String,
    ): Int
}
