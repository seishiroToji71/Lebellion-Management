package uz.lebellion.checklist.web

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.checklist.service.ChecklistTemplateService
import uz.lebellion.common.web.Page
import java.util.UUID

@RestController
@RequestMapping("/api/v1/units/{unitId}/templates")
class ChecklistTemplateController(private val templateService: ChecklistTemplateService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable unitId: UUID,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): Page<ChecklistTemplateResponse> = templateService.list(principal, unitId, limit.coerceIn(1, 100), cursor)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable unitId: UUID,
        @Valid @RequestBody req: CreateChecklistTemplateRequest,
    ): ChecklistTemplateResponse = templateService.create(principal, unitId, req)
}
