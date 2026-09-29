package uz.lebellion.auth.token

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * SHA-256 hex for opaque refresh tokens. We store only the hash; the raw token
 * lives solely on the client, so a DB dump can't be replayed.
 */
@Component
class TokenHasher {

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.toHex()
    }
}

internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

private val HEX = "0123456789abcdef".toCharArray()
