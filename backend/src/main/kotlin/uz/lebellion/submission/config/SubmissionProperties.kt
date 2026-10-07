package uz.lebellion.submission.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `lebellion.submission.*`
 * - [lateWindow] — how long after the deadline a MISSED task still accepts a (late-flagged) submission.
 * - [dupWindowDays] — duplicate-photo comparison window (same item+unit).
 * - [maxPhotoBytes] — hard cap on an uploaded photo's size.
 */
@ConfigurationProperties(prefix = "lebellion.submission")
data class SubmissionProperties(
    val lateWindow: Duration = Duration.ofHours(12),
    val dupWindowDays: Long = 60,
    val maxPhotoBytes: Long = 10L * 1024 * 1024,
)
