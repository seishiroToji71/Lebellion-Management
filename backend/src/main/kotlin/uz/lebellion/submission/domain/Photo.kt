package uz.lebellion.submission.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uz.lebellion.auth.domain.BaseEntity
import java.util.UUID

/**
 * A stored photo. `unitId`/`itemId` are denormalised so duplicate search scopes by (item, unit) over a
 * 60-day window. `storageKey` is an opaque, unpredictable handle into the [StorageService]; the bytes are
 * served only via short-lived signed URLs. `dhash` is a 64-bit perceptual hash (signed long).
 */
@Entity
@Table(name = "photo")
class Photo(
    @Column(name = "organization_id", nullable = false)
    var organizationId: UUID,

    @Column(name = "submission_id", nullable = false)
    var submissionId: UUID,

    @Column(name = "unit_id", nullable = false)
    var unitId: UUID,

    @Column(name = "item_id", nullable = false)
    var itemId: UUID,

    @Column(name = "storage_key", nullable = false, length = 128)
    var storageKey: String,

    @Column(name = "content_type", nullable = false, length = 64)
    var contentType: String,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long,

    @Column(name = "sha256", nullable = false, length = 64)
    var sha256: String,

    @Column(name = "dhash", nullable = false)
    var dhash: Long,
) : BaseEntity()
