package uz.lebellion.auth.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.service.AuthService
import uz.lebellion.auth.service.JoinService
import uz.lebellion.auth.service.RefreshService

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val joinService: JoinService,
    private val refreshService: RefreshService,
    private val clientIpResolver: ClientIpResolver,
) {
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @Valid @RequestBody req: RegisterRequest,
        @RequestHeader("X-Device-Id") deviceId: String,
        http: HttpServletRequest,
    ): AuthResponse = authService.register(req, deviceId, clientIp(http))

    @PostMapping("/join")
    @ResponseStatus(HttpStatus.CREATED)
    fun join(
        @Valid @RequestBody req: JoinRequest,
        @RequestHeader("X-Device-Id") deviceId: String,
        http: HttpServletRequest,
    ): AuthResponse = joinService.join(req, deviceId, clientIp(http))

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody req: RefreshRequest,
        @RequestHeader("X-Device-Id") deviceId: String,
    ): AuthResponse = refreshService.refresh(req.refreshToken, deviceId)

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(
        @Valid @RequestBody req: RefreshRequest,
        @RequestHeader("X-Device-Id") deviceId: String,
    ) = refreshService.logout(req.refreshToken, deviceId)

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody req: LoginRequest,
        @RequestHeader("X-Device-Id") deviceId: String,
        http: HttpServletRequest,
    ): AuthResponse = authService.login(req, deviceId, clientIp(http))

    @PostMapping("/change-password")
    fun changePassword(
        @Valid @RequestBody req: ChangePasswordRequest,
        @RequestHeader("X-Device-Id") deviceId: String,
        @AuthenticationPrincipal principal: AuthPrincipal,
        http: HttpServletRequest,
    ): AuthResponse = authService.changePassword(principal.userId, req, deviceId, clientIp(http))

    private fun clientIp(http: HttpServletRequest): String =
        clientIpResolver.resolve(http.remoteAddr, http.getHeader("X-Forwarded-For"))
}
