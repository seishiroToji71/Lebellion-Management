package uz.lebellion.submission.web

/** 409 — the uploaded photo duplicates an earlier one (exact SHA-256, or a non-static near-duplicate). */
class DuplicatePhotoException : RuntimeException("Duplicate photo")

/** 415 — the uploaded bytes are not a supported image (by magic bytes: only JPEG/PNG). */
class UnsupportedImageException : RuntimeException("Unsupported image type")

/** 413 — the uploaded photo exceeds the configured size limit. */
class PhotoTooLargeException : RuntimeException("Photo too large")

/** 409 — the task is MISSED and the late-submission window has already closed. */
class LateWindowClosedException : RuntimeException("Late submission window closed")
