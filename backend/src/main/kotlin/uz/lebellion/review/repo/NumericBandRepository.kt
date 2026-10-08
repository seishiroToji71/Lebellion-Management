package uz.lebellion.review.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.review.domain.NumericBand
import java.util.UUID

interface NumericBandRepository : JpaRepository<NumericBand, UUID> {
    fun findByOrganizationIdAndItemIdOrderBySortOrderAscIdAsc(organizationId: UUID, itemId: UUID): List<NumericBand>
}
