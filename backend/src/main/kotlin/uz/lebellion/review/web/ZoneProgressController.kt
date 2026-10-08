package uz.lebellion.review.web

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.review.service.ZoneProgressService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/schedules/{scheduleId}")
class ZoneProgressController(private val zoneProgressService: ZoneProgressService) {

    /** "N of M" zone progress for a given period (e.g. a weekly deep-clean's zones). */
    @GetMapping("/progress")
    fun progress(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable scheduleId: UUID,
        @RequestParam periodKey: String,
    ): ZoneProgressResponse = zoneProgressService.progress(principal, scheduleId, periodKey)
}
