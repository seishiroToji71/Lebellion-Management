package uz.lebellion.auth.web

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(private val apiHeadersInterceptor: ApiHeadersInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(apiHeadersInterceptor).addPathPatterns("/api/v1/**")
    }
}
