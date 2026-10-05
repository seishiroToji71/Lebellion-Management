package uz.lebellion.auth.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import uz.lebellion.auth.security.AuthPrincipal

/**
 * Enforces `must_change_password`: while the flag is set, the user may only call
 * `POST /api/v1/auth/change-password` (exempt via the path config in [WebConfig]); every other
 * authenticated endpoint is blocked with 403 `MUST_CHANGE_PASSWORD`.
 *
 * The flag is read from the DB on EVERY request by [uz.lebellion.auth.security.DbBackedJwtAuthenticationConverter],
 * so a freshly refreshed access token carries the same restriction — refresh cannot be used to slip past it.
 */
@Component
class MustChangePasswordGate : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthPrincipal
        if (principal != null && principal.mustChangePassword) throw MustChangePasswordException()
        return true
    }
}
