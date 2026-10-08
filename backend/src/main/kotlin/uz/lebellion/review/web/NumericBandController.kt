package uz.lebellion.review.web

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
import uz.lebellion.review.service.NumericBandService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/items/{itemId}/numeric-bands")
class NumericBandController(private val numericBandService: NumericBandService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable itemId: UUID,
    ): List<NumericBandResponse> = numericBandService.list(principal, itemId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable itemId: UUID,
        @Valid @RequestBody req: CreateNumericBandRequest,
    ): NumericBandResponse = numericBandService.create(principal, itemId, req)
}
