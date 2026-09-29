package uz.lebellion.auth.web

import org.springframework.stereotype.Component
import uz.lebellion.auth.config.AuthProperties

/**
 * Resolves the real client IP behind exactly [AuthProperties.trustedProxyCount] trusted proxies
 * (Caddy). Only the IP appended by our outermost trusted proxy is honoured; anything a client
 * stuffs into X-Forwarded-For to the left of that is ignored, so the value can't be spoofed.
 */
@Component
class ClientIpResolver(props: AuthProperties) {

    private val trustedProxyCount = props.trustedProxyCount

    fun resolve(remoteAddr: String, forwardedFor: String?): String {
        if (trustedProxyCount <= 0 || forwardedFor.isNullOrBlank()) return remoteAddr
        val parts = forwardedFor.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < trustedProxyCount) return remoteAddr
        return parts[parts.size - trustedProxyCount]
    }
}
