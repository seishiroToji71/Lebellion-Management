package uz.lebellion.auth.service

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.config.AuthProperties
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.domain.Organization
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.ratelimit.RateLimiter
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.repo.OrganizationRepository
import uz.lebellion.auth.repo.RefreshTokenRepository
import uz.lebellion.auth.web.AuthResponse
import uz.lebellion.auth.web.ChangePasswordRequest
import uz.lebellion.auth.web.ConflictException
import uz.lebellion.auth.web.InvalidCredentialsException
import uz.lebellion.auth.web.LoginRequest
import uz.lebellion.auth.web.RateLimitedException
import uz.lebellion.auth.web.RegisterRequest
import uz.lebellion.auth.web.RegistrationDisabledException
import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.auth.web.UserProfile
import java.time.Clock
import java.util.UUID

@Service
class AuthService(
    private val organizations: OrganizationRepository,
    private val users: AppUserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val encoder: PasswordEncoder,
    private val tokenIssuer: TokenIssuer,
    private val rateLimiter: RateLimiter,
    private val responses: AuthResponseFactory,
    private val props: AuthProperties,
    private val clock: Clock,
) {
    // Runs a matches() even for unknown accounts to equalize timing (anti-enumeration).
    private val dummyHash: String by lazy { encoder.encode("timing-equalizer")!! }

    @Transactional
    fun register(req: RegisterRequest, deviceId: String, clientIp: String): AuthResponse {
        if (!props.registrationEnabled) throw RegistrationDisabledException()
        rateLimit(limiterCheck("register-ip:$clientIp", "register-ip"))
        val email = req.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val phone = req.phone?.let(::normalizePhone)?.takeIf { it.isNotEmpty() }
        if (email == null && phone == null) throw RequestValidationException("email or phone is required")

        if (organizations.existsByNameIgnoreCase(req.organizationName)) throw ConflictException("Organization name already taken")
        if (email != null && users.existsByEmailIgnoreCase(email)) throw ConflictException("Email already in use")
        if (phone != null && users.existsByPhone(phone)) throw ConflictException("Phone already in use")

        val org = organizations.save(Organization(name = req.organizationName))
        val user = users.save(
            AppUser(
                organizationId = org.id!!,
                name = req.fullName,
                role = Role.FOUNDER,
                email = email,
                phone = phone,
                passwordHash = encoder.encode(req.password),
            ),
        )
        return responses.authResponse(tokenIssuer.issueNewSession(user, deviceId), user)
    }

    fun login(req: LoginRequest, deviceId: String, clientIp: String): AuthResponse {
        val account = normalizeLogin(req.login)
        rateLimit(
            limiterCheck("login-ip:$clientIp", "login-ip"),
            limiterCheck("login-user:$account", "login-user"),
        )
        val user = findByLogin(req.login)
        val hash = user?.passwordHash
        val passwordOk = if (hash != null) encoder.matches(req.password, hash) else {
            encoder.matches(req.password, dummyHash) // constant-ish time
            false
        }
        if (user == null || !passwordOk || !user.isActive) throw InvalidCredentialsException()
        return responses.authResponse(tokenIssuer.issueNewSession(user, deviceId), user)
    }

    @Transactional
    fun changePassword(userId: UUID, req: ChangePasswordRequest, deviceId: String, clientIp: String): AuthResponse {
        rateLimit(
            limiterCheck("change-password-ip:$clientIp", "change-password-ip"),
            limiterCheck("change-password-user:$userId", "change-password-user"),
        )
        val user = users.findById(userId).orElseThrow { InvalidCredentialsException() }
        val hash = user.passwordHash ?: throw InvalidCredentialsException()
        if (!encoder.matches(req.currentPassword, hash)) throw InvalidCredentialsException()

        user.passwordHash = encoder.encode(req.newPassword)
        user.mustChangePassword = false
        user.tokenVersion += 1 // immediately invalidates every existing access token
        users.save(user)
        refreshTokens.revokeAllActiveForUser(user.id!!, clock.instant(), "PASSWORD_CHANGE")
        // fresh session for the current device so the user stays logged in here
        return responses.authResponse(tokenIssuer.issueNewSession(user, deviceId), user)
    }

    @Transactional(readOnly = true)
    fun profile(userId: UUID): UserProfile =
        users.findById(userId).map(responses::profile).orElseThrow { InvalidCredentialsException() }

    private fun rateLimit(vararg checks: RateLimiter.Check) {
        if (!rateLimiter.tryAcquireAll(checks.toList())) throw RateLimitedException(60)
    }

    private fun limiterCheck(key: String, ruleKey: String): RateLimiter.Check {
        val r = props.rule(ruleKey)
        return RateLimiter.Check(key, RateLimiter.Rule(r.limit, r.window))
    }

    private fun findByLogin(login: String): AppUser? {
        val t = login.trim()
        return if (t.contains('@')) users.findByEmailIgnoreCase(t.lowercase()) else users.findByPhone(normalizePhone(t))
    }

    private fun normalizeLogin(login: String): String {
        val t = login.trim()
        return if (t.contains('@')) t.lowercase() else normalizePhone(t)
    }

    // Minimal phone normalization for keying/lookup; full E.164 validation is a later concern.
    private fun normalizePhone(raw: String): String = raw.trim().replace(Regex("[\\s()\\-]"), "")

}
