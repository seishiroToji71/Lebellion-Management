package uz.lebellion.kpi.web

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.kpi.service.ScoreSheetQueryService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/score-sheets")
class ScoreSheetController(private val scoreSheetQueryService: ScoreSheetQueryService) {

    @GetMapping
    fun list(@AuthenticationPrincipal principal: AuthPrincipal): List<ScoreSheetSummary> =
        scoreSheetQueryService.list(principal)

    @GetMapping("/{sheetId}")
    fun get(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sheetId: UUID,
    ): ScoreSheetResponse = scoreSheetQueryService.get(principal, sheetId)
}
