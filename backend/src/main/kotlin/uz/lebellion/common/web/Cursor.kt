package uz.lebellion.common.web

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * Opaque keyset cursor over `(createdAt, id)`. Encodes the exact instant (nanosecond precision, so it
 * round-trips against the timestamptz boundary) plus the id tiebreaker as base64url of `"<isoInstant>|<uuid>"`.
 * Keyset (not offset) pagination: stable under concurrent inserts and cheap on an ordered index.
 */
object CursorCodec {
    private const val SEP = "|"

    fun encode(createdAt: Instant, id: UUID): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${DateTimeFormatter.ISO_INSTANT.format(createdAt)}$SEP$id".toByteArray(UTF_8))

    /** @throws IllegalArgumentException if the cursor is not a value produced by [encode]. */
    fun decode(cursor: String): Key {
        val raw = try {
            String(Base64.getUrlDecoder().decode(cursor), UTF_8)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("malformed cursor")
        }
        val parts = raw.split(SEP)
        require(parts.size == 2) { "malformed cursor" }
        return try {
            Key(Instant.parse(parts[0]), UUID.fromString(parts[1]))
        } catch (_: Exception) {
            throw IllegalArgumentException("malformed cursor")
        }
    }

    data class Key(val createdAt: Instant, val id: UUID)
}

/** A page of items plus the cursor to fetch the next page (`null` when the list is exhausted). */
data class Page<T>(val items: List<T>, val nextCursor: String?)

/**
 * Builds a [Page] from rows fetched with `limit + 1`: if the extra row is present there is a next page,
 * and its cursor is taken from the last item actually returned.
 */
inline fun <E, T> pageOf(
    rows: List<E>,
    limit: Int,
    createdAt: (E) -> Instant,
    id: (E) -> UUID,
    map: (E) -> T,
): Page<T> {
    val hasMore = rows.size > limit
    val slice = if (hasMore) rows.subList(0, limit) else rows
    val nextCursor = if (hasMore) slice.last().let { CursorCodec.encode(createdAt(it), id(it)) } else null
    return Page(slice.map(map), nextCursor)
}
