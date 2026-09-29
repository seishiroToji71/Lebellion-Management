package uz.lebellion.auth.token

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 (keyed with a server secret) for invite codes and password-reset codes.
 * Keyed hashing (not plain SHA-256) so a stolen DB can't brute-force low-entropy codes
 * without also stealing the secret.
 */
class HmacCodec(secret: String) {

    private val keySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), ALGORITHM)

    fun hmacHex(value: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    /** Constant-time comparison to avoid leaking timing about the stored hash. */
    fun matches(value: String, expectedHex: String): Boolean =
        MessageDigest.isEqual(
            hmacHex(value).toByteArray(StandardCharsets.UTF_8),
            expectedHex.toByteArray(StandardCharsets.UTF_8),
        )

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
