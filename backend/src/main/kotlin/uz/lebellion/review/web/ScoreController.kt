package uz.lebellion.review.web

import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.review.service.ScoreService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/task-instances/{taskInstanceId}")
class ScoreController(private val scoreService: ScoreService) {

    /** MANUAL score: FULL/PARTIAL/ZERO -> points x {1,0.5,0}. Requires can_score; no self-scoring. */
    @PostMapping("/manual-score")
    fun manual(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable taskInstanceId: UUID,
        @Valid @RequestBody req: ManualScoreRequest,
    ): TaskScoreResponse = scoreService.scoreManual(principal, taskInstanceId, req.grade)

    /** NUMERIC score: the value is mapped through the item's bands to points. Requires can_score. */
    @PostMapping("/numeric-score")
    fun numeric(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable taskInstanceId: UUID,
        @Valid @RequestBody req: NumericScoreRequest,
    ): TaskScoreResponse = scoreService.scoreNumeric(principal, taskInstanceId, req.value)
}
