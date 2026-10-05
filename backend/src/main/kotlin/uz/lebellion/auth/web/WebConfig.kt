package uz.lebellion.auth.web

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val apiHeadersInterceptor: ApiHeadersInterceptor,
    private val mustChangePasswordGate: MustChangePasswordGate,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(apiHeadersInterceptor).addPathPatterns("/api/v1/**")
        // Block every authenticated data endpoint while must_change_password is set. All /api/v1/auth/*
        // endpoints are exempt: the token-free ones need no principal, and change-password is exactly the
        // one the user must reach to clear the flag. Refresh therefore cannot bypass the restriction —
        // the flag is re-read from the DB on the next data request (DbBackedJwtAuthenticationConverter).
        registry.addInterceptor(mustChangePasswordGate)
            .addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/auth/**")
    }
}
