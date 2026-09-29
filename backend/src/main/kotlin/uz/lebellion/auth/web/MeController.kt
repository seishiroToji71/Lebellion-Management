package uz.lebellion.auth.web

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.service.AuthService

@RestController
@RequestMapping("/api/v1/me")
class MeController(private val authService: AuthService) {
    @GetMapping
    fun me(@AuthenticationPrincipal principal: AuthPrincipal): UserProfile = authService.profile(principal.userId)
}
