package uz.lebellion.kpi.web

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.kpi.service.KpiCatalogSeeder

@RestController
@RequestMapping("/api/v1/kpi")
class KpiSeedController(private val seeder: KpiCatalogSeeder) {

    /** Seed/refresh the KPI catalog into the caller's org from the configured JSON (FOUNDER only, idempotent). */
    @PostMapping("/seed")
    fun seed(@AuthenticationPrincipal principal: AuthPrincipal): KpiSeedResult = seeder.seed(principal)
}
