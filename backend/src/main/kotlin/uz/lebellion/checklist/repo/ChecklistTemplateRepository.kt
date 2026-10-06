package uz.lebellion.checklist.repo

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import uz.lebellion.checklist.domain.ChecklistTemplate
import java.time.Instant
import java.util.UUID

/** Keyset pagination over `(createdAt, id)`, pinned to one unit. */
interface ChecklistTemplateRepository : JpaRepository<ChecklistTemplate, UUID> {

    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): ChecklistTemplate?

    @Query(
        """
        select t from ChecklistTemplate t
         where t.organizationId = :orgId
           and t.unitId = :unitId
           and (t.createdAt > :afterCreatedAt or (t.createdAt = :afterCreatedAt and t.id > :afterId))
         order by t.createdAt asc, t.id asc
        """,
    )
    fun pageInUnit(
        @Param("orgId") orgId: UUID,
        @Param("unitId") unitId: UUID,
        @Param("afterCreatedAt") afterCreatedAt: Instant,
        @Param("afterId") afterId: UUID,
        pageable: Pageable,
    ): List<ChecklistTemplate>
}
