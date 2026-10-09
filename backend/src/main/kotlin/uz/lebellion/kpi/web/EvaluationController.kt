package uz.lebellion.kpi.web

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.kpi.service.EvaluationService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/evaluations")
class EvaluationController(private val evaluationService: EvaluationService) {

    /** Open (or return) a DRAFT monthly evaluation for an employee against a score sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody req: CreateEvaluationRequest,
    ): EvaluationResponse = evaluationService.create(principal, req)

    @GetMapping("/{evaluationId}")
    fun get(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable evaluationId: UUID,
    ): EvaluationResponse = evaluationService.get(principal, evaluationId)

    /** Set a line's award (∈ {0, points/2, points}). DRAFT only; FINALIZED is immutable. */
    @PostMapping("/{evaluationId}/awards")
    fun setAward(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable evaluationId: UUID,
        @Valid @RequestBody req: SetAwardRequest,
    ): EvaluationResponse = evaluationService.setAward(principal, evaluationId, req)

    /** Freeze the evaluation: sum awards, resolve the band (half-up), snapshot, recommend. */
    @PostMapping("/{evaluationId}/finalize")
    fun finalize(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable evaluationId: UUID,
    ): EvaluationResponse = evaluationService.finalize(principal, evaluationId)
}
