package uz.lebellion.auth.notify

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Dev-only stub: logs that a code would be sent, WITHOUT the raw destination (no personal data
 * in logs, per legal rules). The message body (which carries the code) is logged so developers
 * can complete flows locally. Replace with a real provider in prod.
 */
@Component
class LoggingNotificationSender : NotificationSender {

    private val log = LoggerFactory.getLogger(LoggingNotificationSender::class.java)

    override fun send(channel: NotificationSender.Channel, destination: String, message: String) {
        log.info("[DEV NOTIFY] channel={} destination={} message={}", channel, mask(destination), message)
    }

    /** Keep only the first and last character; enough to eyeball routing, not to identify a person. */
    private fun mask(destination: String): String {
        if (destination.length <= 2) return "**"
        return "${destination.first()}***${destination.last()}"
    }
}
