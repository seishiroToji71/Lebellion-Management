package uz.lebellion.auth.web

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/** Thrown when the client's X-App-Version is below the minimum supported version → HTTP 426. */
@ResponseStatus(HttpStatus.UPGRADE_REQUIRED)
class UpgradeRequiredException(message: String = "UPGRADE_REQUIRED") : RuntimeException(message)
