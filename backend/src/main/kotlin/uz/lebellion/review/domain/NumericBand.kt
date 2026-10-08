package uz.lebellion.review.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.math.BigDecimal
import java.util.UUID

/**
 * One band of a NUMERIC item's scoring scale: `[lowerBound, upperBound)` — lower inclusive, upper
 * exclusive. A null `lowerBound` means -infinity, a null `upperBound` means +infinity. A value scores the
 * [points] of the band it falls into.
 */
@Entity
@Table(name = "numeric_band")
class NumericBand(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "item_id", nullable = false)
    var itemId: UUID,

    @Column(name = "lower_bound")
    var lowerBound: BigDecimal? = null,

    @Column(name = "upper_bound")
    var upperBound: BigDecimal? = null,

    @Column(name = "points", nullable = false)
    var points: Int,

    @Column(name = "label", length = 64)
    var label: String? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity() {
    /** `[lowerBound, upperBound)` membership. */
    fun contains(value: BigDecimal): Boolean =
        (lowerBound == null || value >= lowerBound) && (upperBound == null || value < upperBound)
}
