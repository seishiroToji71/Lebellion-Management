package uz.lebellion.auth.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TokenHasherTest {

    private val hasher = TokenHasher()

    @Test
    fun `sha256 matches known vector for abc`() {
        // FIPS 180-2 test vector for "abc"
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hasher.sha256Hex("abc"),
        )
    }

    @Test
    fun `hash is deterministic and differs per input`() {
        assertEquals(hasher.sha256Hex("token-value"), hasher.sha256Hex("token-value"))
        assertNotEquals(hasher.sha256Hex("token-a"), hasher.sha256Hex("token-b"))
    }

    @Test
    fun `output is 64 lowercase hex chars`() {
        val hex = hasher.sha256Hex("anything")
        assertEquals(64, hex.length)
        assertEquals(hex, hex.lowercase())
        assert(hex.all { it in "0123456789abcdef" })
    }
}
