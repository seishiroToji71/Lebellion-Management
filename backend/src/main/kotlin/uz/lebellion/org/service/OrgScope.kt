package uz.lebellion.org.service

import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.ForbiddenException
import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.common.web.CursorCodec
import java.time.Instant
import java.util.UUID

/** Ordered before any real row, so the keyset predicate can be applied unconditionally on the first page. */
private val MIN_UUID: UUID = UUID(0L, 0L)

/** Org-management endpoints are for FOUNDER and BRANCH_MANAGER only; an EMPLOYEE is refused. */
internal fun requireFounderOrManager(principal: AuthPrincipal) {
    if (principal.role != Role.FOUNDER && principal.role != Role.BRANCH_MANAGER) {
        throw ForbiddenException("not permitted for this role")
    }
}

/**
 * The branch a caller is confined to: a BRANCH_MANAGER's own branch, or null for a FOUNDER (whole org).
 * A manager row always carries a branch (DB invariant); a missing one is treated as forbidden rather than
 * silently widening the scope to the whole organization.
 */
internal fun branchScopeOf(principal: AuthPrincipal): UUID? = when (principal.role) {
    Role.BRANCH_MANAGER -> principal.branchId ?: throw ForbiddenException("manager is not bound to a branch")
    else -> null
}

/** Turns an optional page cursor into a concrete `(createdAt, id)` floor; a malformed cursor is a 400. */
internal fun cursorFloor(cursor: String?): Pair<Instant, UUID> {
    val key = cursor?.let {
        try {
            CursorCodec.decode(it)
        } catch (_: IllegalArgumentException) {
            throw RequestValidationException("invalid cursor")
        }
    }
    return (key?.createdAt ?: Instant.EPOCH) to (key?.id ?: MIN_UUID)
}
