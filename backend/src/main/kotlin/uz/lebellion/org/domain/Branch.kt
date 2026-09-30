package uz.lebellion.org.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/** A physical branch of an organization. Every row carries organization_id and is queried scoped by it. */
@Entity
@Table(name = "branch")
class Branch(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "name", nullable = false, length = 255)
    var name: String,

    @Column(name = "address")
    var address: String? = null,
) : BaseEntity()
