package uz.lebellion.checklist.domain

/**
 * Kind of a checklist item (and of a library criterion):
 * - [PHOTO]   — an employee uploads a photo, a reviewer accepts/rejects it.
 * - [MANUAL]  — a reviewer scores it directly, no photo.
 * - [NUMERIC] — a monthly number; the score comes from a configurable scale (added in P2-6).
 */
enum class ItemType {
    PHOTO,
    MANUAL,
    NUMERIC,
}
