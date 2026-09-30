package uz.lebellion.auth.domain

/**
 * Values written to `refresh_token.revoked_reason` (VARCHAR(40)). Kept as distinct strings — never
 * collapse theft causes into one — so the Founder's signals stay separable:
 *  - [DEVICE_MISMATCH] — a refresh arrived from a device other than the one the family is bound to;
 *  - [TOKEN_REUSE]     — a rotated/revoked token was replayed outside the grace window.
 * Both revoke the whole family, but they are different attack shapes.
 */
object RevocationReason {
    const val LOGOUT = "LOGOUT"
    const val TOKEN_REUSE = "TOKEN_REUSE"
    const val DEVICE_MISMATCH = "DEVICE_MISMATCH"
    const val EXPIRED = "EXPIRED"

    /** Refresh presented for a user who is gone/deactivated — kill the family (instant lockout). */
    const val USER_INACTIVE = "USER_INACTIVE"
}
