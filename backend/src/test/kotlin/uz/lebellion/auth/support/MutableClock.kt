package uz.lebellion.auth.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A hand-cranked [Clock] for deterministic time-travel in integration tests — lets us prove sliding
 * inactivity and the 90-day absolute cap without sleeping. Import [MutableClockConfig] and autowire
 * [MutableClock] to advance it; it wins over the production `authClock` bean via @Primary.
 */
class MutableClock(private var current: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(z: ZoneId): Clock = MutableClock(current, z)
    override fun instant(): Instant = current
    fun setTo(instant: Instant) { current = instant }
    fun advance(duration: Duration) { current = current.plus(duration) }
}

@TestConfiguration
class MutableClockConfig {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(Instant.now())
}
