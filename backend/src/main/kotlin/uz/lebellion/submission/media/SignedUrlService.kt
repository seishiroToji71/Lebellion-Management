package uz.lebellion.submission.media

import org.springframework.stereotype.Service
import uz.lebellion.submission.config.MediaProperties
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Builds and verifies short-lived signed photo URLs: `/media/{storageKey}?exp=<epochSec>&sig=<b64url>`.
 * The signature is HMAC-SHA256 over `"<storageKey>:<exp>"`. No DB or session is consulted on verify —
 * the URL itself is the capability, and it expires. Comparison is constant-time.
 */
@Service
class SignedUrlService(
    private val props: MediaProperties,
    private val clock: Clock,
) {
    private val key = SecretKeySpec(props.signingSecret.toByteArray(UTF_8), "HmacSHA256")

    fun sign(storageKey: String): String {
        val exp = Instant.now(clock).plus(props.urlTtl).epochSecond
        return "/media/$storageKey?exp=$exp&sig=${mac(storageKey, exp)}"
    }

    /** True iff [sig] matches and [exp] is still in the future (per the server clock). */
    fun verify(storageKey: String, exp: Long, sig: String): Boolean {
        if (Instant.now(clock).epochSecond > exp) return false
        val expected = mac(storageKey, exp).toByteArray(UTF_8)
        val given = sig.toByteArray(UTF_8)
        return MessageDigest.isEqual(expected, given)
    }

    private fun mac(storageKey: String, exp: Long): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(key)
        val raw = m.doFinal("$storageKey:$exp".toByteArray(UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
}
