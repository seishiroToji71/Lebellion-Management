package uz.lebellion.submission.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `lebellion.media.*` — [signingSecret] keys the HMAC of signed photo URLs; [urlTtl] is their lifetime.
 */
@ConfigurationProperties(prefix = "lebellion.media")
data class MediaProperties(
    val signingSecret: String = "dev-insecure-media-signing-secret-change-me",
    val urlTtl: Duration = Duration.ofMinutes(5),
)
