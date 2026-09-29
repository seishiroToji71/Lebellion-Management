package uz.lebellion.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * One-time invite. `codeHmac` is HMAC-SHA256 of the plaintext code (never stored in the clear).
 * A recovery invite carries `targetEmployeeId` (re-binds an existing user); a new BRANCH_MANAGER
 * invite carries `email`/`phone` (the manager's future login identity, set by the founder).
 */
@Entity
@Table(name = "invite")
class Invite(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "code_hmac", nullable = false, length = 64)
    var codeHmac: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    var role: Role,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: InviteStatus = InviteStatus.PENDING,

    @Column(name = "branch_id")
    var branchId: UUID? = null,

    @Column(name = "unit_id")
    var unitId: UUID? = null,

    @Column(name = "target_employee_id")
    var targetEmployeeId: UUID? = null,

    @Column(name = "email", length = 320)
    var email: String? = null,

    @Column(name = "phone", length = 20)
    var phone: String? = null,

    @Column(name = "used_by")
    var usedBy: UUID? = null,

    @Column(name = "used_at")
    var usedAt: Instant? = null,
) : BaseEntity()
