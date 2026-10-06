package uz.lebellion.schedule.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import uz.lebellion.schedule.config.ScheduleProperties

/**
 * Runs the generator once a day (early morning, Asia/Tashkent). Generation is idempotent, so the exact
 * time is not critical. Disabled when `lebellion.schedule.generation-enabled=false`.
 */
@Component
class ScheduleGenerationJob(
    private val generator: ScheduleGenerator,
    private val props: ScheduleProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Tashkent")
    fun run() {
        if (!props.generationEnabled) return
        val created = generator.generate()
        if (created > 0) log.info("schedule generation created {} task instances", created)
    }
}
