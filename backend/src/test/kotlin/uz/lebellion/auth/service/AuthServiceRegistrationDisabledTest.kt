package uz.lebellion.auth.service

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.security.crypto.password.PasswordEncoder
import uz.lebellion.auth.config.AuthProperties
import uz.lebellion.auth.ratelimit.RateLimiter
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.repo.OrganizationRepository
import uz.lebellion.auth.repo.RefreshTokenRepository
import uz.lebellion.auth.web.RegisterRequest
import uz.lebellion.auth.web.RegistrationDisabledException
import java.time.Clock

class AuthServiceRegistrationDisabledTest {

    @Test
    fun `register is refused when registration is disabled`() {
        val service = AuthService(
            organizations = mock(OrganizationRepository::class.java),
            users = mock(AppUserRepository::class.java),
            refreshTokens = mock(RefreshTokenRepository::class.java),
            encoder = mock(PasswordEncoder::class.java),
            tokenIssuer = mock(TokenIssuer::class.java),
            rateLimiter = mock(RateLimiter::class.java),
            props = AuthProperties(registrationEnabled = false),
            clock = Clock.systemUTC(),
        )

        assertThrows(RegistrationDisabledException::class.java) {
            service.register(
                RegisterRequest("Acme", "Founder", "f@example.com", null, "sup3rsecret!"),
                "device-1",
            )
        }
    }
}
