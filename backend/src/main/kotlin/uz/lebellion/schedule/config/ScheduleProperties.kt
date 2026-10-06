package uz.lebellion.schedule.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `lebellion.schedule.*`
 * - [horizonDays] — how far ahead the generator materialises task instances (default 14).
 * - [generationEnabled] — master switch for the scheduled generation job (default on).
 * - [missedSweepEnabled] — master switch for the scheduled MISSED sweep job (default on).
 */
@ConfigurationProperties(prefix = "lebellion.schedule")
data class ScheduleProperties(
    val horizonDays: Long = 14,
    val generationEnabled: Boolean = true,
    val missedSweepEnabled: Boolean = true,
)
