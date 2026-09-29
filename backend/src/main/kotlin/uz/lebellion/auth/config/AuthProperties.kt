package uz.lebellion.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding
import java.time.Duration

/**
 * All auth tunables come from configuration/env — nothing security-relevant is hard-coded.
 * Secrets carry insecure dev defaults in application.yml and MUST be overridden in prod.
 *
 * Nested classes have a single parameterized constructor (no defaults ⇒ no synthetic Kotlin no-arg
 * constructor) and are explicitly @ConstructorBinding so Spring binds them via the constructor
 * instead of falling back to JavaBean (setter) binding, which fails on `val` properties.
 * The outer class keeps defaults for test ergonomics; it binds as a value object either way.
 */
@ConfigurationProperties(prefix = "lebellion.auth")
data class AuthProperties(
    val jwt: Jwt = Jwt("", Duration.ofMinutes(15)),
    val refresh: Refresh = Refresh(Duration.ofDays(60), Duration.ofDays(30), Duration.ofDays(90)),
    val invite: Invite = Invite("", Duration.ofDays(7)),
    val passwordReset: PasswordReset = PasswordReset("", Duration.ofMinutes(15), 5),
    /** Self-serve organization registration; disabled by default (see contract). */
    val registrationEnabled: Boolean = false,
    /** Lowest client version the server still accepts; older clients get 426. */
    val minAppVersion: String = "1.0.0",
    /** How many reverse proxies (Caddy) sit in front of the app; used to resolve the real client IP. */
    val trustedProxyCount: Int = 1,
    /** Rate-limit rules keyed by dimension (e.g. `login-ip`, `login-user`). */
    val rateLimit: Map<String, RateLimitRule> = emptyMap(),
) {
    /** Rule for a dimension key, with a conservative fallback if unconfigured. */
    fun rule(key: String): RateLimitRule = rateLimit[key] ?: RateLimitRule(5, Duration.ofMinutes(1))

    data class Jwt @ConstructorBinding constructor(
        val secret: String,
        val accessTtl: Duration,
    )

    data class Refresh @ConstructorBinding constructor(
        val employeeSliding: Duration,
        val managerSliding: Duration,
        val managerAbsolute: Duration,
    )

    data class Invite @ConstructorBinding constructor(
        val hmacSecret: String,
        val ttl: Duration,
    )

    data class PasswordReset @ConstructorBinding constructor(
        val hmacSecret: String,
        val codeTtl: Duration,
        val maxAttempts: Int,
    )

    data class RateLimitRule @ConstructorBinding constructor(
        val limit: Int,
        val window: Duration,
    )
}
