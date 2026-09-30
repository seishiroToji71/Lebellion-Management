package uz.lebellion.auth.web

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Maps domain + framework exceptions to the shared `Error` body with stable codes. */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException::class)
    fun invalidCredentials(e: InvalidCredentialsException) =
        error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials")

    @ExceptionHandler(InvalidTokenException::class)
    fun invalidToken(e: InvalidTokenException) =
        error(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid token")

    @ExceptionHandler(RegistrationDisabledException::class)
    fun registrationDisabled(e: RegistrationDisabledException) =
        error(HttpStatus.FORBIDDEN, "REGISTRATION_DISABLED", "Registration is disabled")

    @ExceptionHandler(ConflictException::class)
    fun conflict(e: ConflictException) =
        error(HttpStatus.CONFLICT, "CONFLICT", e.message ?: "Conflict")

    @ExceptionHandler(InviteAlreadyUsedException::class)
    fun inviteAlreadyUsed(e: InviteAlreadyUsedException) =
        error(HttpStatus.CONFLICT, "INVITE_ALREADY_USED", "This invite code has already been used")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun dataIntegrity(e: DataIntegrityViolationException) =
        error(HttpStatus.CONFLICT, "CONFLICT", "Already exists")

    @ExceptionHandler(RequestValidationException::class)
    fun requestValidation(e: RequestValidationException) =
        error(HttpStatus.BAD_REQUEST, "VALIDATION", e.message ?: "Invalid request")

    @ExceptionHandler(MissingDeviceIdException::class)
    fun missingDeviceId(e: MissingDeviceIdException) =
        error(HttpStatus.BAD_REQUEST, "DEVICE_ID_REQUIRED", "X-Device-Id is required")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun beanValidation(e: MethodArgumentNotValidException) =
        error(HttpStatus.BAD_REQUEST, "VALIDATION", e.bindingResult.fieldErrors.firstOrNull()?.let { "${it.field}: ${it.defaultMessage}" } ?: "Invalid request")

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(e: MissingRequestHeaderException) =
        error(HttpStatus.BAD_REQUEST, "VALIDATION", "Missing header: ${e.headerName}")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        error(HttpStatus.BAD_REQUEST, "VALIDATION", "Malformed request body")

    @ExceptionHandler(UpgradeRequiredException::class)
    fun upgradeRequired(e: UpgradeRequiredException) =
        error(HttpStatus.UPGRADE_REQUIRED, "UPGRADE_REQUIRED", "Please update the app to continue")

    @ExceptionHandler(RateLimitedException::class)
    fun rateLimited(e: RateLimitedException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", e.retryAfterSeconds.toString())
            .body(ApiError("RATE_LIMITED", "Too many requests"))

    private fun error(status: HttpStatus, code: String, message: String): ResponseEntity<ApiError> =
        ResponseEntity.status(status).body(ApiError(code, message))
}
