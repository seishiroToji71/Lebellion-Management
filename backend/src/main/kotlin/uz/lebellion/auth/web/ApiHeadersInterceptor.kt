package uz.lebellion.auth.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Enforces the two client contract headers on the versioned API:
 *  - X-App-Version on every request (too old -> 426),
 *  - X-Device-Id on session-establishing auth endpoints (missing -> 400).
 * The health endpoint lives outside the versioned API and is therefore exempt (matches the contract).
 */
@Component
class ApiHeadersInterceptor(private val versionGate: AppVersionGate) : HandlerInterceptor {

    private val deviceIdRequiredPaths = setOf(
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/join",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/auth/change-password",
    )

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        versionGate.require(request.getHeader("X-App-Version")) // throws UpgradeRequiredException → 426
        if (request.requestURI in deviceIdRequiredPaths && request.getHeader("X-Device-Id").isNullOrBlank()) {
            throw MissingDeviceIdException()
        }
        return true
    }
}
