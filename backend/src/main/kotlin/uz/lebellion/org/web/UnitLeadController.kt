package uz.lebellion.org.web

import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.org.service.UnitLeadService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/units/{unitId}/lead")
class UnitLeadController(private val unitLeadService: UnitLeadService) {

    @GetMapping
    fun get(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable unitId: UUID,
    ): UnitLeadResponse = unitLeadService.getLead(principal, unitId)

    @PutMapping
    fun set(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable unitId: UUID,
        @Valid @RequestBody req: SetUnitLeadRequest,
    ): UnitLeadResponse = unitLeadService.setLead(principal, unitId, req)
}
