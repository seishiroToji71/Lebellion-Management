package uz.lebellion.submission.media

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.submission.repo.PhotoRepository
import uz.lebellion.submission.storage.LocalDiskStorageService
import uz.lebellion.submission.storage.StorageService
import java.time.Duration

/**
 * Serves photo bytes for a valid signed URL. Lives OUTSIDE `/api/v1` (like `/health`) so it is exempt
 * from the app-version gate and can be loaded by an <img> tag; it is permit-all in SecurityConfig and
 * authorised solely by the signature. An invalid/expired signature or a bad key is a flat 403 — no leak.
 */
@RestController
class MediaController(
    private val signedUrls: SignedUrlService,
    private val photos: PhotoRepository,
    private val storage: StorageService,
) {
    @GetMapping("/media/{storageKey}")
    fun get(
        @PathVariable storageKey: String,
        @RequestParam exp: Long,
        @RequestParam sig: String,
    ): ResponseEntity<ByteArray> {
        if (!storageKey.matches(LocalDiskStorageService.KEY_REGEX)) return forbidden()
        if (!signedUrls.verify(storageKey, exp, sig)) return forbidden()
        val photo = photos.findByStorageKey(storageKey) ?: return forbidden()
        val bytes = storage.load(storageKey)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(photo.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
            .body(bytes)
    }

    private fun forbidden(): ResponseEntity<ByteArray> = ResponseEntity.status(HttpStatus.FORBIDDEN).build()
}
