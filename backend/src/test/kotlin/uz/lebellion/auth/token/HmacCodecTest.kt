package uz.lebellion.auth.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HmacCodecTest {

    @Test
    fun `matches RFC 4231-style known vector`() {
        // key="key", data="The quick brown fox jumps over the lazy dog"
        val codec = HmacCodec("key")
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            codec.hmacHex("The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun `same secret is deterministic, different secret diverges`() {
        val a = HmacCodec("secret-one")
        val b = HmacCodec("secret-two")
        assertEquals(a.hmacHex("code123"), a.hmacHex("code123"))
        assertNotEquals(a.hmacHex("code123"), b.hmacHex("code123"))
    }

    @Test
    fun `matches verifies stored hash`() {
        val codec = HmacCodec("invite-secret")
        val stored = codec.hmacHex("ABCDEFGHJK")
        assertTrue(codec.matches("ABCDEFGHJK", stored))
        assertFalse(codec.matches("WRONGCODE1", stored))
    }

    @Test
    fun `matches is false and safe for wrong-length input`() {
        val codec = HmacCodec("s")
        assertFalse(codec.matches("x", "deadbeef"))
    }
}
