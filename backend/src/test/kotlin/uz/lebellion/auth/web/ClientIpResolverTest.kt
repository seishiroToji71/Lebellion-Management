package uz.lebellion.auth.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uz.lebellion.auth.config.AuthProperties

class ClientIpResolverTest {

    private fun resolver(trustedProxyCount: Int) =
        ClientIpResolver(AuthProperties(trustedProxyCount = trustedProxyCount))

    @Test
    fun `no forwarded header falls back to remote address`() {
        assertEquals("10.0.0.1", resolver(1).resolve("10.0.0.1", null))
        assertEquals("10.0.0.1", resolver(1).resolve("10.0.0.1", "   "))
    }

    @Test
    fun `with one trusted proxy takes the rightmost forwarded ip`() {
        assertEquals("203.0.113.7", resolver(1).resolve("10.0.0.1", "203.0.113.7"))
    }

    @Test
    fun `client-spoofed left entries are ignored`() {
        // Client injected "1.1.1.1"; Caddy appended the real peer "203.0.113.7".
        assertEquals("203.0.113.7", resolver(1).resolve("10.0.0.1", "1.1.1.1, 203.0.113.7"))
        assertEquals("203.0.113.7", resolver(1).resolve("10.0.0.1", "9.9.9.9, 8.8.8.8, 203.0.113.7"))
    }

    @Test
    fun `two trusted proxies pick the ip appended by the outermost one`() {
        assertEquals("203.0.113.7", resolver(2).resolve("10.0.0.1", "203.0.113.7, 172.16.0.1"))
    }

    @Test
    fun `zero trusted proxies never trusts forwarded header`() {
        assertEquals("10.0.0.1", resolver(0).resolve("10.0.0.1", "203.0.113.7"))
    }

    @Test
    fun `fewer entries than trusted proxies falls back to remote address`() {
        assertEquals("10.0.0.1", resolver(2).resolve("10.0.0.1", "203.0.113.7"))
    }
}
