package uz.lebellion.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "organization")
class Organization(
    @Column(name = "name", nullable = false, length = 255)
    var name: String,
) : BaseEntity()
