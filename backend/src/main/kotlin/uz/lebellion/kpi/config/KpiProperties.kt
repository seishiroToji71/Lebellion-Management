package uz.lebellion.kpi.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `lebellion.kpi.*` — filesystem paths to the local, gitignored seed inputs. Unset in dev/CI (seeding is
 * a deliberate, FOUNDER-triggered action); set via env in the client environment. Tests point these at a
 * small fixture under src/test/resources.
 */
@ConfigurationProperties(prefix = "lebellion.kpi")
data class KpiProperties(
    val catalogPath: String? = null,
    val bandsPath: String? = null,
)
