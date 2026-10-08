package uz.lebellion.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * Single users table for all roles. Role-specific shape (branch/unit/password presence) is enforced
 * by CHECK constraints in the DB (V2); this entity just maps the columns.
 */
@Entity
@Table(name = "app_user")
class AppUser(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "name", nullable = false, length = 255)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    var role: Role,

    @Column(name = "email", length = 320)
    var email: String? = null,

    @Column(name = "phone", length = 20)
    var phone: String? = null,

    @Column(name = "branch_id")
    var branchId: UUID? = null,

    @Column(name = "unit_id")
    var unitId: UUID? = null,

    @Column(name = "password_hash", length = 255)
    var passwordHash: String? = null,

    @Column(name = "must_change_password", nullable = false)
    var mustChangePassword: Boolean = false,

    /** Granular grant: may enter MANUAL/NUMERIC scores. FOUNDER always may, regardless of this flag. */
    @Column(name = "can_score", nullable = false)
    var canScore: Boolean = false,

    /** Granular grant: may review (accept/reject) submissions. FOUNDER always may, as does a unit lead. */
    @Column(name = "can_review", nullable = false)
    var canReview: Boolean = false,

    @Column(name = "token_version", nullable = false)
    var tokenVersion: Int = 0,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,
) : BaseEntity()
