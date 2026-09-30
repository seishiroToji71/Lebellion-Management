package uz.lebellion.auth.repo

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.domain.Invite
import uz.lebellion.auth.domain.Organization
import uz.lebellion.auth.domain.RefreshToken
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
}

interface InviteRepository : JpaRepository<Invite, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invite i where i.codeHmac = :hmac")
    fun findByCodeHmacForUpdate(@Param("hmac") hmac: String): Invite?
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
