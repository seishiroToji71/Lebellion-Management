package uz.lebellion.auth.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureCodeGeneratorTest {

    private val gen = SecureCodeGenerator()

    @Test
    fun `invite code uses only the unambiguous alphabet and requested length`() {
        val allowed = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toSet()
        repeat(200) {
            val code = gen.inviteCode()
            assertEquals(10, code.length)
            assertTrue(code.all { it in allowed }, "unexpected char in $code")
        }
    }

    @Test
    fun `invite codes are highly unlikely to collide`() {
        val codes = (1..5000).map { gen.inviteCode() }.toSet()
        assertEquals(5000, codes.size)
    }

    @Test
    fun `invite code rejects too-short length`() {
        assertThrows(IllegalArgumentException::class.java) { gen.inviteCode(7) }
    }

    @Test
    fun `opaque token is url-safe and unpadded`() {
        val token = gen.opaqueToken()
        assertTrue(token.isNotEmpty())
        assertTrue(token.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `numeric code has requested number of digits`() {
        val code = gen.numericCode(6)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }
}
