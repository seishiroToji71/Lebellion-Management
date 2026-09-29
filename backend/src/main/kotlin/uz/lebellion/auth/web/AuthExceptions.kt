package uz.lebellion.auth.web

/** Identical 401 for unknown principal / wrong secret / deactivated — no enumeration. */
class InvalidCredentialsException : RuntimeException("Invalid credentials")

class RegistrationDisabledException : RuntimeException("Registration is disabled")

class ConflictException(message: String) : RuntimeException(message)

/** Join with an already-used code from the SAME device that consumed it — deterministic, not enumeration. */
class InviteAlreadyUsedException : RuntimeException("Invite already used")

class RequestValidationException(message: String) : RuntimeException(message)

class MissingDeviceIdException : RuntimeException("X-Device-Id is required")

class RateLimitedException(val retryAfterSeconds: Long) : RuntimeException("Too many requests")
