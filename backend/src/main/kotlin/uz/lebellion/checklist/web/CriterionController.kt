package uz.lebellion.checklist.web

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.checklist.service.CriterionService
import uz.lebellion.common.web.Page

@RestController
@RequestMapping("/api/v1/criteria")
class CriterionController(private val criterionService: CriterionService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): Page<CriterionResponse> = criterionService.list(principal, limit.coerceIn(1, 100), cursor)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody req: CreateCriterionRequest,
    ): CriterionResponse = criterionService.create(principal, req)
}
