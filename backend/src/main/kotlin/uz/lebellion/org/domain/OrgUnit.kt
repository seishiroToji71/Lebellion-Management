package uz.lebellion.org.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/**
 * A unit (подразделение: Kitchen, Hall, ...) under a branch. Named `OrgUnit` (not `Unit`) to avoid
 * shadowing Kotlin's `kotlin.Unit`; the table stays `unit`.
 */
@Entity
@Table(name = "unit")
class OrgUnit(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "branch_id", nullable = false)
    var branchId: UUID,

    @Column(name = "name", nullable = false, length = 255)
    var name: String,

    /** Unit lead (e.g. the kitchen's chef); null => fall back to the branch manager. */
    @Column(name = "lead_employee_id")
    var leadEmployeeId: UUID? = null,

    /** Temporary stand-in; takes precedence over [leadEmployeeId] while set. */
    @Column(name = "acting_lead_employee_id")
    var actingLeadEmployeeId: UUID? = null,
) : BaseEntity()
