package uz.lebellion.submission.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Pure unit coverage of the hashing/validation primitives (no Spring, no DB): magic-byte detection,
 * SHA-256 determinism, and that dHash is stable under re-compression/crop yet far for a different scene.
 */
class DHasherTest {

    private fun image(f: (x: Int, y: Int) -> Int): BufferedImage {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) for (x in 0 until 64) {
            val v = f(x, y).coerceIn(0, 255)
            img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        return img
    }

    private fun encode(img: BufferedImage, fmt: String): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(img, fmt, out)
        return out.toByteArray()
    }

    @Test
    fun `detects jpeg and png by signature and rejects non-images`() {
        val horizontal = image { x, _ -> x * 4 }
        assertEquals("image/png", detectImageContentType(encode(horizontal, "png")))
        assertEquals("image/jpeg", detectImageContentType(encode(horizontal, "jpg")))
        assertNull(detectImageContentType("not an image".toByteArray()))
    }

    @Test
    fun `sha256 is deterministic and content-sensitive`() {
        val a = "hello".toByteArray()
        assertEquals(sha256Hex(a), sha256Hex("hello".toByteArray()))
        assertTrue(sha256Hex(a) != sha256Hex("world".toByteArray()))
    }

    @Test
    fun `dHash is stable under re-compression and crop but far for a different scene`() {
        // a "tent" that rises then falls in x produces real left>right bits; the same tent in y (constant
        // along each row) produces a very different hash — a monotonic ramp would be degenerate (all zeros).
        val horizontal = image { x, _ -> 255 - Math.abs(x - 32) * 8 }
        val vertical = image { _, y -> 255 - Math.abs(y - 32) * 8 }

        val basePng = DHasher.hash(encode(horizontal, "png"))
        val recompressedJpg = DHasher.hash(encode(horizontal, "jpg"))
        val croppedScaled = run {
            val sub = horizontal.getSubimage(2, 2, 60, 60)
            val scaled = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics(); g.drawImage(sub, 0, 0, 64, 64, null); g.dispose()
            DHasher.hash(encode(scaled, "png"))
        }
        val differentScene = DHasher.hash(encode(vertical, "png"))

        assertTrue(DHasher.hamming(basePng, recompressedJpg) <= 6, "re-compression stays within threshold")
        assertTrue(DHasher.hamming(basePng, croppedScaled) <= 6, "a small crop stays within threshold")
        assertTrue(DHasher.hamming(basePng, differentScene) > 6, "a different scene is clearly beyond threshold")
    }
}
