package uz.lebellion.schedule.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import uz.lebellion.schedule.config.ScheduleProperties

/**
 * Sweeps overdue PENDING tasks to MISSED every few minutes. Server time is authoritative. Disabled when
 * `lebellion.schedule.missed-sweep-enabled=false`.
 */
@Component
class MissedSweepJob(
    private val sweeper: MissedSweeper,
    private val props: ScheduleProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Tashkent")
    fun run() {
        if (!props.missedSweepEnabled) return
        val missed = sweeper.sweep()
        if (missed > 0) log.info("missed sweep marked {} task instances", missed)
    }
}
