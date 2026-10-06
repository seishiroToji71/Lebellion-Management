package uz.lebellion.checklist.repo

import org.springframework.data.jpa.repository.JpaRepository
import uz.lebellion.checklist.domain.ChecklistItem
import java.util.UUID

/**
 * Items of a template are a bounded list (a single checklist), so they are returned whole, ordered by
 * `(sortOrder, id)` — not keyset-paginated like the org-wide template/criterion lists.
 */
interface ChecklistItemRepository : JpaRepository<ChecklistItem, UUID> {

    fun findByOrganizationIdAndTemplateIdOrderBySortOrderAscIdAsc(
        organizationId: UUID,
        templateId: UUID,
    ): List<ChecklistItem>
}
