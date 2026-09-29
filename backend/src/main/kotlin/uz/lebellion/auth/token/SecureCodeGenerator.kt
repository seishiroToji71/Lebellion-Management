package uz.lebellion.auth.token

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/**
 * CSPRNG-backed generators for opaque refresh tokens, invite codes and numeric reset codes.
 * Invite alphabet omits ambiguous glyphs (0/O/1/I/L) so codes can be dictated by phone.
 */
@Component
class SecureCodeGenerator(private val random: SecureRandom = SecureRandom()) {

    /** 32 symbols → 5 bits each. Default length 10 ⇒ 50 bits of entropy (>= 40 required). */
    fun inviteCode(length: Int = 10): String {
        require(length >= 8) { "invite code needs >= 8 chars for >= 40 bits" }
        val sb = StringBuilder(length)
        repeat(length) { sb.append(INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)]) }
        return sb.toString()
    }

    /** URL-safe, unpadded base64 of [bytes] random bytes. Default 32 bytes = 256 bits. */
    fun opaqueToken(bytes: Int = 32): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    /** Numeric confirmation code (password reset). Low entropy on purpose — guarded by TTL + attempt lockout. */
    fun numericCode(digits: Int = 6): String {
        require(digits in 4..10) { "numeric code digits out of range" }
        val sb = StringBuilder(digits)
        repeat(digits) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    private companion object {
        const val INVITE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    }
}
