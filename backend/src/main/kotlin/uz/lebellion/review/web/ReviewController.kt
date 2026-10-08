package uz.lebellion.review.web

import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.review.service.ReviewService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/submissions/{submissionId}")
class ReviewController(private val reviewService: ReviewService) {

    /** Accept/reject a submission. can_review required; reviewer may not review own work; FOUNDER overrides. */
    @PostMapping("/review")
    fun review(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable submissionId: UUID,
        @Valid @RequestBody req: ReviewRequest,
    ): ReviewResponse = reviewService.review(principal, submissionId, req)
}
