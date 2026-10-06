package uz.lebellion.checklist.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/**
 * A checklist template under a Unit. A unit may own MANY templates (one per position, e.g. «Повар
 * мангалщик»). Role/position binding is expressed by the template itself; `positionId` is reserved for
 * the future positions model (P2-2) and stays null until then.
 */
@Entity
@Table(name = "checklist_template")
class ChecklistTemplate(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "unit_id", nullable = false)
    var unitId: UUID,

    @Column(name = "name", nullable = false, length = 255)
    var name: String,

    @Column(name = "position_id")
    var positionId: UUID? = null,
) : BaseEntity()
