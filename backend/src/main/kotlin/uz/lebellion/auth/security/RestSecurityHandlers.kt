package uz.lebellion.auth.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/** 401 with the shared Error body (instead of Spring Security's default empty/WWW-Authenticate response). */
@Component
class RestAuthenticationEntryPoint : AuthenticationEntryPoint {
    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required")
    }
}

/** 403 with the shared Error body. */
@Component
class RestAccessDeniedHandler : AccessDeniedHandler {
    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied")
    }
}

// code/message here are fixed ASCII constants, so no JSON escaping is required.
private fun writeError(response: HttpServletResponse, status: HttpStatus, code: String, message: String) {
    response.status = status.value()
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.characterEncoding = "UTF-8"
    response.writer.write("""{"code":"$code","message":"$message"}""")
}
