package uz.lebellion.common.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CursorCodecTest {

    @Test
    fun `encode then decode round-trips instant (nanos) and id`() {
        val createdAt = Instant.parse("2026-09-30T12:34:56.123456Z")
        val id = UUID.randomUUID()

        val key = CursorCodec.decode(CursorCodec.encode(createdAt, id))

        assertEquals(createdAt, key.createdAt)
        assertEquals(id, key.id)
    }

    @Test
    fun `a malformed cursor is rejected with IllegalArgumentException`() {
        // not valid base64, valid base64 without the separator, and empty all fail the same way
        assertThrows(IllegalArgumentException::class.java) { CursorCodec.decode("@@@not-base64@@@") }
        assertThrows(IllegalArgumentException::class.java) { CursorCodec.decode("abcd") }
        assertThrows(IllegalArgumentException::class.java) { CursorCodec.decode("") }
    }

    @Test
    fun `pageOf returns no cursor when the extra row is absent`() {
        val rows = listOf(1, 2, 3)
        val page = pageOf(rows, limit = 3, createdAt = { Instant.EPOCH }, id = { UUID.randomUUID() }) { it }
        assertEquals(listOf(1, 2, 3), page.items)
        assertNull(page.nextCursor)
    }

    @Test
    fun `pageOf trims the over-fetched row and emits a cursor`() {
        val id = UUID.randomUUID()
        val at = Instant.parse("2026-01-01T00:00:00Z")
        // 4 rows fetched for a limit of 3 => one page of 3 + a cursor pointing at the 3rd row
        val page = pageOf(listOf(1, 2, 3, 4), limit = 3, createdAt = { at }, id = { id }) { it }
        assertEquals(listOf(1, 2, 3), page.items)
        assertEquals(CursorCodec.encode(at, id), page.nextCursor)
    }
}
