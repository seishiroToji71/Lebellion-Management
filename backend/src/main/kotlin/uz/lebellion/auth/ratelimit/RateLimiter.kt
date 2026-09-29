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

    data class Check(val key: String, val rule: Rule)

    /**
     * Enforces several buckets at once, e.g. `action:ip` AND `action:user`. Every bucket is charged
     * for the attempt (a login attempt counts against both the IP and the account); the request is
     * allowed only if none of the buckets is exhausted. No short-circuit on purpose — an attempt must
     * count against the account key even when the IP key still has room, and vice versa. This is what
     * stops password guessing against a known account from rotating IPs.
     */
    fun tryAcquireAll(checks: List<Check>): Boolean {
        var allowed = true
        for (c in checks) {
            if (!tryAcquire(c.key, c.rule)) allowed = false
        }
        return allowed
    }
}
