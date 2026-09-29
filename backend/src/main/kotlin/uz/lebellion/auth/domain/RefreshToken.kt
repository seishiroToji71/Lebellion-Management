package uz.lebellion.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * One row per issued refresh token. Rotation chains share a [familyId]; only the hash is stored.
 * Has created_at but no updated_at (rows are appended and then marked rotated/revoked in place).
 */
@Entity
@Table(name = "refresh_token")
class RefreshToken(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "family_id", nullable = false)
    var familyId: UUID,

    // char(64) in the DB — pin the JDBC type so schema validation matches (not varchar).
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64)
    var tokenHash: String,

    @Column(name = "device_id", nullable = false, length = 255)
    var deviceId: String,

    @Column(name = "issued_at", nullable = false)
    var issuedAt: Instant,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "parent_id")
    var parentId: UUID? = null,

    @Column(name = "absolute_expires_at")
    var absoluteExpiresAt: Instant? = null,

    @Column(name = "rotated_at")
    var rotatedAt: Instant? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "revoked_reason", length = 40)
    var revokedReason: String? = null,
) {
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
