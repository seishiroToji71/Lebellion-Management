package uz.lebellion.org.web

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.common.web.Page
import uz.lebellion.org.service.UnitService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/units")
class UnitController(private val unitService: UnitService) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) branchId: UUID?,
    ): Page<UnitResponse> = unitService.list(principal, limit.coerceIn(1, 100), cursor, branchId)
}
