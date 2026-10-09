package uz.lebellion.auth.web

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import uz.lebellion.schedule.web.TaskCancelledException
import uz.lebellion.schedule.web.TaskNotOpenException
import uz.lebellion.kpi.web.EvaluationFinalizedException
import uz.lebellion.kpi.web.InvalidAwardException
import uz.lebellion.review.web.AlreadyReviewedException
import uz.lebellion.review.web.CannotReviewOwnException
import uz.lebellion.review.web.CannotScoreOwnException
import uz.lebellion.review.web.NotScorableException
import uz.lebellion.submission.web.DuplicatePhotoException
import uz.lebellion.submission.web.LateWindowClosedException
import uz.lebellion.submission.web.PhotoTooLargeException
import uz.lebellion.submission.web.UnsupportedImageException

/** Maps domain + framework exceptions to the shared `Error` body with stable codes. */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(TaskCancelledException::class)
    fun taskCancelled(e: TaskCancelledException) =
        error(HttpStatus.CONFLICT, "TASK_CANCELLED", "This task was cancelled")

    @ExceptionHandler(TaskNotOpenException::class)
    fun taskNotOpen(e: TaskNotOpenException) =
        error(HttpStatus.CONFLICT, "TASK_NOT_OPEN", "Task is not open for submission")

    @ExceptionHandler(DuplicatePhotoException::class)
    fun duplicatePhoto(e: DuplicatePhotoException) =
        error(HttpStatus.CONFLICT, "DUPLICATE_PHOTO", "Duplicate photo")

    @ExceptionHandler(LateWindowClosedException::class)
    fun lateWindowClosed(e: LateWindowClosedException) =
        error(HttpStatus.CONFLICT, "LATE_WINDOW_CLOSED", "Late submission window closed")

    @ExceptionHandler(UnsupportedImageException::class)
    fun unsupportedImage(e: UnsupportedImageException) =
        error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE", "Only JPEG and PNG are accepted")

    @ExceptionHandler(PhotoTooLargeException::class)
    fun photoTooLarge(e: PhotoTooLargeException) =
        error(HttpStatus.CONTENT_TOO_LARGE, "PHOTO_TOO_LARGE", "Photo too large")

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun maxUpload(e: MaxUploadSizeExceededException) =
        error(HttpStatus.CONTENT_TOO_LARGE, "PHOTO_TOO_LARGE", "Photo too large")

    @ExceptionHandler(CannotReviewOwnException::class)
    fun cannotReviewOwn(e: CannotReviewOwnException) =
        error(HttpStatus.FORBIDDEN, "CANNOT_REVIEW_OWN", "Cannot review your own submission")

    @ExceptionHandler(AlreadyReviewedException::class)
    fun alreadyReviewed(e: AlreadyReviewedException) =
        error(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "Submission is already reviewed")

    @ExceptionHandler(CannotScoreOwnException::class)
    fun cannotScoreOwn(e: CannotScoreOwnException) =
        error(HttpStatus.FORBIDDEN, "CANNOT_SCORE_OWN", "Cannot score your own task")

    @ExceptionHandler(NotScorableException::class)
    fun notScorable(e: NotScorableException) =
        error(HttpStatus.BAD_REQUEST, "NOT_SCORABLE", e.message ?: "Not scorable")

    @ExceptionHandler(EvaluationFinalizedException::class)
    fun evaluationFinalized(e: EvaluationFinalizedException) =
        error(HttpStatus.CONFLICT, "EVALUATION_FINALIZED", "Evaluation is finalized")

    @ExceptionHandler(InvalidAwardException::class)
    fun invalidAward(e: InvalidAwardException) =
        error(HttpStatus.BAD_REQUEST, "INVALID_AWARD", e.message ?: "Invalid award")

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

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) =
        error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.message ?: "Not found")

    @ExceptionHandler(ForbiddenException::class)
    fun forbidden(e: ForbiddenException) =
        error(HttpStatus.FORBIDDEN, "FORBIDDEN", e.message ?: "Forbidden")

    @ExceptionHandler(MustChangePasswordException::class)
    fun mustChangePassword(e: MustChangePasswordException) =
        error(HttpStatus.FORBIDDEN, "MUST_CHANGE_PASSWORD", "Password change required")

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
