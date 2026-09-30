package uz.lebellion.org.web

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
import uz.lebellion.common.web.Page
import uz.lebellion.org.service.BranchService
import uz.lebellion.org.service.UnitService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/branches")
class BranchController(
    private val branchService: BranchService,
    private val unitService: UnitService,
) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): Page<BranchResponse> = branchService.list(principal, limit.coerceIn(1, 100), cursor)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody req: CreateBranchRequest,
    ): BranchResponse = branchService.create(principal, req)

    @PostMapping("/{branchId}/units")
    @ResponseStatus(HttpStatus.CREATED)
    fun createUnit(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable branchId: UUID,
        @Valid @RequestBody req: CreateUnitRequest,
    ): UnitResponse = unitService.create(principal, branchId, req)
}
