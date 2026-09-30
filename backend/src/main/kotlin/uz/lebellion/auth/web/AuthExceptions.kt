package uz.lebellion.auth.web

/** Identical 401 for unknown principal / wrong secret / deactivated — no enumeration. */
class InvalidCredentialsException : RuntimeException("Invalid credentials")

/** 401 for refresh: token unknown / expired / rotated-outside-grace / revoked / device mismatch. */
class InvalidTokenException : RuntimeException("Invalid token")

class RegistrationDisabledException : RuntimeException("Registration is disabled")

class ConflictException(message: String) : RuntimeException(message)

/** Join with an already-used code from the SAME device that consumed it — deterministic, not enumeration. */
class InviteAlreadyUsedException : RuntimeException("Invite already used")

class RequestValidationException(message: String) : RuntimeException(message)

/** 404 — a resource does not exist, or lies outside the caller's tenant/branch scope (no existence leak). */
class NotFoundException(message: String) : RuntimeException(message)

/** 403 — authenticated but the role (or branch scope) does not permit this action. */
class ForbiddenException(message: String) : RuntimeException(message)

class MissingDeviceIdException : RuntimeException("X-Device-Id is required")

class RateLimitedException(val retryAfterSeconds: Long) : RuntimeException("Too many requests")
