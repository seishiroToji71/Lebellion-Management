package uz.lebellion.checklist.web

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.checklist.service.ChecklistItemService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/templates/{templateId}/items")
class ChecklistItemController(private val itemService: ChecklistItemService) {

    /** A template's items are a bounded list, returned whole (ordered by sortOrder), not paginated. */
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable templateId: UUID,
    ): List<ChecklistItemResponse> = itemService.list(principal, templateId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable templateId: UUID,
        @Valid @RequestBody req: CreateChecklistItemRequest,
    ): ChecklistItemResponse = itemService.create(principal, templateId, req)
}
