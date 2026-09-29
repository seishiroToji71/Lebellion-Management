package uz.lebellion.auth.web

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uz.lebellion.auth.config.AuthProperties

class AppVersionGateTest {

    private val gate = AppVersionGate(AuthProperties(minAppVersion = "1.2.0"))

    @Test
    fun `equal or newer versions are supported`() {
        assertTrue(gate.isSupported("1.2.0"))
        assertTrue(gate.isSupported("1.2.1"))
        assertTrue(gate.isSupported("1.3.0"))
        assertTrue(gate.isSupported("2.0.0"))
    }

    @Test
    fun `older versions are rejected`() {
        assertFalse(gate.isSupported("1.1.9"))
        assertFalse(gate.isSupported("1.0.0"))
        assertFalse(gate.isSupported("0.9.9"))
    }

    @Test
    fun `missing or malformed version is rejected`() {
        assertFalse(gate.isSupported(null))
        assertFalse(gate.isSupported(""))
        assertFalse(gate.isSupported("abc"))
        assertFalse(gate.isSupported("1.2.3.4"))
    }

    @Test
    fun `require throws UpgradeRequiredException for unsupported version`() {
        assertThrows(UpgradeRequiredException::class.java) { gate.require("1.0.0") }
        assertThrows(UpgradeRequiredException::class.java) { gate.require(null) }
    }

    @Test
    fun `require passes for supported version`() {
        gate.require("1.2.0")
    }
}
