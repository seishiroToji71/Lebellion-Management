package uz.lebellion.auth.ratelimit

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory fixed-window rate limiter (single VPS, no external store by design).
 * Callers pass the rule per action; keys are typically "action:clientIp".
 */
@Component
class RateLimiter(private val clock: Clock) {

    data class Rule(val limit: Int, val window: Duration)

    private data class Window(var startMillis: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /** @return true if allowed (a slot was consumed), false if the limit is exhausted for the window. */
    fun tryAcquire(key: String, rule: Rule): Boolean {
        val now = clock.millis()
        val windowMs = rule.window.toMillis()
        var allowed = false
        windows.compute(key) { _, existing ->
            val w = if (existing == null || now - existing.startMillis >= windowMs) {
                Window(now, 0)
            } else {
                existing
            }
            if (w.count < rule.limit) {
                w.count += 1
                allowed = true
            }
            w
        }
        return allowed
    }
}
