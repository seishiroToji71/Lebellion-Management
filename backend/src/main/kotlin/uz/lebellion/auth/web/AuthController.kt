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

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val clientIpResolver: ClientIpResolver,
) {
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @Valid @RequestBody req: RegisterRequest,
        @RequestHeader("X-Device-Id") deviceId: String,
    ): AuthResponse = authService.register(req, deviceId)

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
