package uz.lebellion.auth.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableConfigurationProperties(AuthProperties::class)
class SecurityConfig(private val props: AuthProperties) {

    /** DelegatingPasswordEncoder — default id is bcrypt; supports transparent upgrades later. */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun jwtSecretKey(): SecretKeySpec {
        val bytes = props.jwt.secret.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size >= 32) { "lebellion.auth.jwt.secret must be >= 32 bytes for HS256" }
        return SecretKeySpec(bytes, "HmacSHA256")
    }

    @Bean
    fun jwtEncoder(jwtSecretKey: SecretKeySpec): JwtEncoder =
        NimbusJwtEncoder(ImmutableSecret<SecurityContext>(jwtSecretKey))

    @Bean
    fun jwtDecoder(jwtSecretKey: SecretKeySpec): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/health").permitAll()
                // Token-free auth endpoints that establish or restore a session. Explicit allow-list
                // (deny-by-default): anything not listed — incl. /api/v1/me and future /auth/* that
                // need a token — stays authenticated.
                it.requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/join",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/password-reset/request",
                    "/api/v1/auth/password-reset/confirm",
                ).permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { rs -> rs.jwt { } }
        return http.build()
    }
}
