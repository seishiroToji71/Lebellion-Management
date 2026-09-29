package uz.lebellion.auth.web

import org.springframework.stereotype.Component
import uz.lebellion.auth.config.AuthProperties

/**
 * Compares the client's X-App-Version against the configured minimum (semver x.y.z).
 * A missing or malformed version is treated as unsupported.
 */
@Component
class AppVersionGate(props: AuthProperties) {

    private val minimum = parse(props.minAppVersion)
        ?: error("lebellion.auth.min-app-version is not a valid semver: ${props.minAppVersion}")

    fun isSupported(version: String?): Boolean {
        val parsed = version?.let { parse(it) } ?: return false
        return compare(parsed, minimum) >= 0
    }

    fun require(version: String?) {
        if (!isSupported(version)) throw UpgradeRequiredException()
    }

    private fun parse(raw: String): IntArray? {
        val parts = raw.trim().split(".")
        if (parts.isEmpty() || parts.size > 3) return null
        val nums = IntArray(3)
        for (i in parts.indices) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n < 0) return null
            nums[i] = n
        }
        return nums
    }

    private fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0 until 3) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }
}
