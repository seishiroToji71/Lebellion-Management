package uz.lebellion.review.web

/** 403 — a reviewer may not review their own submission (as submitter or a tagged helper). */
class CannotReviewOwnException : RuntimeException("Cannot review your own submission")

/** 409 — the submission is already decided and the caller is not a FOUNDER (only FOUNDER may override). */
class AlreadyReviewedException : RuntimeException("Submission is already reviewed")

/** 403 — a scorer may not score a task they submitted or helped on. */
class CannotScoreOwnException : RuntimeException("Cannot score your own task")

/** 400 — the task's item is not scorable this way (wrong item type, or no matching numeric band). */
class NotScorableException(message: String) : RuntimeException(message)
