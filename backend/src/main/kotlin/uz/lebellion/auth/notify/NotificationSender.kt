package uz.lebellion.auth.notify

/**
 * Delivers out-of-band codes (invite codes, password-reset codes). v1 ships only a dev stub;
 * a real email/SMS provider can be plugged in later without touching callers.
 */
interface NotificationSender {

    fun send(channel: Channel, destination: String, message: String)

    enum class Channel { EMAIL, SMS }
}
