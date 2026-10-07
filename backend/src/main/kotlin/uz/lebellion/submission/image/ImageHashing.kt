package uz.lebellion.submission.image

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Lowercase hex SHA-256 of the raw bytes. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Detects the image type by its MAGIC BYTES (never by the client-supplied Content-Type) and returns the
 * canonical content type, or null if it is not a supported image. Only JPEG and PNG are accepted.
 */
fun detectImageContentType(bytes: ByteArray): String? = when {
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
        "image/jpeg"
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() ->
        "image/png"
    else -> null
}

/**
 * A 64-bit difference hash (dHash): downscale to 9x8 grayscale and, per row, set a bit where a pixel is
 * brighter than its right neighbour (8 comparisons x 8 rows = 64 bits). Robust to re-compression, minor
 * crops and rescales; sensitive to a genuinely different scene. Implemented here (no heavy dependency).
 */
object DHasher {

    fun hash(bytes: ByteArray): Long {
        val source = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("not a decodable image")
        val small = BufferedImage(9, 8, BufferedImage.TYPE_INT_RGB)
        val g = small.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(source, 0, 0, 9, 8, null)
        g.dispose()

        val gray = Array(8) { y -> IntArray(9) { x -> luminance(small.getRGB(x, y)) } }
        var bits = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (gray[y][x] > gray[y][x + 1]) bits = bits or (1L shl bit)
                bit++
            }
        }
        return bits
    }

    /** Hamming distance between two 64-bit hashes (number of differing bits). */
    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun luminance(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        // integer Rec. 601 luma
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
