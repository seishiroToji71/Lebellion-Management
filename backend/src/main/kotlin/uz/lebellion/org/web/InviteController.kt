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
import uz.lebellion.org.service.InviteService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/invites")
class InviteController(private val inviteService: InviteService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody req: CreateInviteRequest,
    ): InviteResponse = inviteService.create(principal, req)

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) status: InviteStatusView?,
    ): Page<InviteSummary> = inviteService.list(principal, limit.coerceIn(1, 100), cursor, status)

    @PostMapping("/{inviteId}/reissue")
    fun reissue(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable inviteId: UUID,
        @Valid @RequestBody(required = false) req: ReissueInviteRequest?,
    ): InviteResponse = inviteService.reissue(principal, inviteId, req ?: ReissueInviteRequest())

    @PostMapping("/{inviteId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable inviteId: UUID,
    ) = inviteService.revoke(principal, inviteId)
}
