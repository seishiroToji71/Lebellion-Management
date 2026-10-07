package uz.lebellion.submission.web

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.submission.service.SubmissionHelperService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/submissions/{submissionId}")
class SubmissionHelperController(private val helperService: SubmissionHelperService) {

    /** The tagged employee confirms their participation ("I did help"). Only the taggee may call this. */
    @PostMapping("/confirm")
    fun confirm(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable submissionId: UUID,
    ): HelperRef = helperService.confirm(principal, submissionId)
}
