package uz.lebellion.submission.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.lebellion.auth.audit.AuditLogRecorder
import uz.lebellion.auth.domain.Role
import uz.lebellion.auth.repo.AppUserRepository
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.NotFoundException
import uz.lebellion.auth.web.RequestValidationException
import uz.lebellion.checklist.domain.ChecklistItem
import uz.lebellion.checklist.repo.ChecklistItemRepository
import uz.lebellion.org.repo.UnitRepository
import uz.lebellion.schedule.domain.TaskInstance
import uz.lebellion.schedule.domain.TaskStatus
import uz.lebellion.schedule.notify.NotificationOutboxWriter
import uz.lebellion.schedule.repo.TaskInstanceRepository
import uz.lebellion.schedule.service.TaskAttemptAuditor
import uz.lebellion.schedule.web.TaskCancelledException
import uz.lebellion.schedule.web.TaskNotOpenException
import uz.lebellion.submission.config.SubmissionProperties
import uz.lebellion.submission.domain.BlockedReason
import uz.lebellion.submission.domain.Photo
import uz.lebellion.submission.domain.Submission
import uz.lebellion.submission.domain.SubmissionHelper
import uz.lebellion.submission.domain.SubmissionStatus
import uz.lebellion.submission.image.DHasher
import uz.lebellion.submission.image.detectImageContentType
import uz.lebellion.submission.image.sha256Hex
import uz.lebellion.submission.media.SignedUrlService
import uz.lebellion.submission.repo.PhotoRepository
import uz.lebellion.submission.repo.SubmissionHelperRepository
import uz.lebellion.submission.repo.SubmissionRepository
import uz.lebellion.submission.storage.StorageService
import uz.lebellion.submission.web.DuplicatePhotoException
import uz.lebellion.submission.web.HelperRef
import uz.lebellion.submission.web.LateWindowClosedException
import uz.lebellion.submission.web.PhotoRef
import uz.lebellion.submission.web.PhotoTooLargeException
import uz.lebellion.submission.web.SubmissionResponse
import uz.lebellion.submission.web.UnsupportedImageException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Full submission (replaces the P2-4 minimal submit): validate the photo (magic bytes + size), run
 * duplicate protection, store the bytes, persist the submission + helper tags, advance the task, and
 * emit audit + outbox events (identifiers only). Server time is authoritative throughout.
 */
@Service
class PhotoSubmissionService(
    private val taskInstances: TaskInstanceRepository,
    private val checklistItems: ChecklistItemRepository,
    private val units: UnitRepository,
    private val users: AppUserRepository,
    private val submissions: SubmissionRepository,
    private val photos: PhotoRepository,
    private val helpers: SubmissionHelperRepository,
    private val storage: StorageService,
    private val signedUrls: SignedUrlService,
    private val audit: AuditLogRecorder,
    private val outbox: NotificationOutboxWriter,
    private val attemptAuditor: TaskAttemptAuditor,
    private val blockedAttempts: BlockedAttemptRecorder,
    private val flags: SubmissionFlagWriter,
    private val props: SubmissionProperties,
    private val clock: java.time.Clock,
) {

    @Transactional
    fun submit(
        principal: AuthPrincipal,
        taskInstanceId: UUID,
        answer: Boolean,
        helperUserIds: List<UUID>,
        photoBytes: ByteArray?,
    ): SubmissionResponse {
        val now = Instant.now(clock)
        val task = taskInstances.findByIdAndOrganizationId(taskInstanceId, principal.organizationId)
            ?: throw NotFoundException("task not found")
        requireInScope(principal, task)
        val item = checklistItems.findByIdAndOrganizationId(task.itemId, principal.organizationId)
            ?: throw NotFoundException("task not found")

        // --- status machine (server time; client time ignored) ---
        val keepMissed = resolveSubmittable(principal, task, now)
        val late = now.isAfter(task.dueAt)

        // --- validate helpers BEFORE storing anything (avoid an orphaned blob on a 400) ---
        val helperIds = helperUserIds.distinct()
        for (hid in helperIds) {
            val helper = users.findByIdAndOrganizationId(hid, principal.organizationId)
                ?: throw RequestValidationException("tagged helper is not in this organization")
            if (helper.unitId != task.unitId) throw RequestValidationException("tagged helper must be a member of the unit")
        }

        // --- photo: decode, hash, duplicate-check (before storing) ---
        var computed: ComputedPhoto? = null
        var flagged = false
        if (photoBytes != null && photoBytes.isNotEmpty()) {
            if (photoBytes.size > props.maxPhotoBytes) throw PhotoTooLargeException()
            val contentType = detectImageContentType(photoBytes) ?: throw UnsupportedImageException()
            val sha = sha256Hex(photoBytes)
            val dhash = DHasher.hash(photoBytes)
            flagged = runDuplicateChecks(principal, task, item, sha, dhash, now)
            computed = ComputedPhoto(photoBytes, contentType, sha, dhash)
        } else if (task.photoRequired) {
            throw RequestValidationException("a photo is required for this task")
        }

        // --- persist ---
        val submission = submissions.save(
            Submission(
                organizationId = principal.organizationId,
                taskInstanceId = task.id!!,
                submittedByUserId = principal.userId,
                answer = answer,
                receivedAt = now,
                late = late,
                status = SubmissionStatus.SUBMITTED,
            ),
        )

        val photoRef = computed?.let { c ->
            val key = storage.store(c.bytes, c.contentType)
            val photo = photos.save(
                Photo(
                    organizationId = principal.organizationId,
                    submissionId = submission.id!!,
                    unitId = task.unitId,
                    itemId = task.itemId,
                    storageKey = key,
                    contentType = c.contentType,
                    sizeBytes = c.bytes.size.toLong(),
                    sha256 = c.sha,
                    dhash = c.dhash,
                ),
            )
            PhotoRef(photo.id!!, signedUrls.sign(key), c.contentType, photo.sizeBytes)
        }

        val helperRows = helperIds.map { hid ->
            helpers.save(SubmissionHelper(principal.organizationId, submission.id!!, hid, confirmedAt = null))
        }

        if (flagged) {
            flags.setFlags(submission.id!!, mapOf("near_duplicate" to true, "static_scene" to true))
        }

        // advance the task: PENDING -> SUBMITTED; a MISSED task stays MISSED (the reviewer decides)
        if (!keepMissed && task.status == TaskStatus.PENDING) {
            task.status = TaskStatus.SUBMITTED
            taskInstances.save(task)
        }

        audit.record(
            organizationId = principal.organizationId,
            eventType = "TASK_SUBMITTED",
            actorUserId = principal.userId,
            targetType = "SUBMISSION",
            targetId = submission.id,
            metadata = mapOf("taskInstanceId" to task.id.toString(), "late" to late, "flagged" to flagged),
        )
        // outbox payload: identifiers only — no names, phones or photo links
        outbox.write(
            principal.organizationId,
            "TASK_SUBMITTED",
            mapOf(
                "submissionId" to submission.id.toString(),
                "taskInstanceId" to task.id.toString(),
                "unitId" to task.unitId.toString(),
            ),
        )

        return SubmissionResponse(
            id = submission.id!!,
            taskInstanceId = task.id!!,
            submittedByUserId = submission.submittedByUserId,
            answer = submission.answer,
            receivedAt = submission.receivedAt,
            late = submission.late,
            status = submission.status,
            flagged = flagged,
            photo = photoRef,
            helpers = helperRows.map { HelperRef(it.employeeId, it.confirmedAt) },
        )
    }

    /** @return true if the task is MISSED (accepted as a late submission, status preserved). */
    private fun resolveSubmittable(principal: AuthPrincipal, task: TaskInstance, now: Instant): Boolean =
        when (task.status) {
            TaskStatus.CANCELLED -> {
                attemptAuditor.recordRejected(task.organizationId, principal.userId, task.id!!, "CANCELLED")
                throw TaskCancelledException()
            }
            TaskStatus.PENDING -> false
            TaskStatus.MISSED -> {
                if (now.isAfter(task.dueAt.plus(props.lateWindow))) {
                    attemptAuditor.recordRejected(task.organizationId, principal.userId, task.id!!, "LATE_WINDOW_CLOSED")
                    throw LateWindowClosedException()
                }
                true
            }
            else -> {
                attemptAuditor.recordRejected(task.organizationId, principal.userId, task.id!!, task.status.name)
                throw TaskNotOpenException(task.status)
            }
        }

    /** @return true when a static-scene near-duplicate should be FLAGGED (allowed). Throws 409 on a block. */
    private fun runDuplicateChecks(
        principal: AuthPrincipal,
        task: TaskInstance,
        item: ChecklistItem,
        sha: String,
        dhash: Long,
        now: Instant,
    ): Boolean {
        val cutoff = now.minus(Duration.ofDays(props.dupWindowDays))
        // exact SHA-256 is always a duplicate (includes rejected submissions)
        if (photos.existsExact(principal.organizationId, task.unitId, task.itemId, sha, cutoff)) {
            blockedAttempts.record(task.organizationId, task.id!!, task.itemId, task.unitId, principal.userId, sha, dhash, BlockedReason.EXACT_SHA)
            throw DuplicatePhotoException()
        }
        // near-duplicate (excludes rejected submissions) — per-item Hamming threshold
        val nearMatch = photos
            .nearDupCandidateHashes(principal.organizationId, task.unitId, task.itemId, cutoff)
            .any { DHasher.hamming(it, dhash) <= item.dhashThreshold }
        if (nearMatch) {
            if (item.staticScene) return true // flag for reviewer attention, do not block
            blockedAttempts.record(task.organizationId, task.id!!, task.itemId, task.unitId, principal.userId, sha, dhash, BlockedReason.NEAR_DUPLICATE)
            throw DuplicatePhotoException()
        }
        return false
    }

    /** FOUNDER: any unit; BRANCH_MANAGER: own branch; EMPLOYEE: own unit. Out of scope reads as 404. */
    private fun requireInScope(principal: AuthPrincipal, task: TaskInstance) {
        when (principal.role) {
            Role.FOUNDER -> return
            Role.BRANCH_MANAGER -> {
                val unit = units.findByIdAndOrganizationId(task.unitId, principal.organizationId)
                if (unit == null || unit.branchId != principal.branchId) throw NotFoundException("task not found")
            }
            Role.EMPLOYEE -> {
                val me = users.findByIdAndOrganizationId(principal.userId, principal.organizationId)
                if (me == null || me.unitId != task.unitId) throw NotFoundException("task not found")
            }
        }
    }

    private data class ComputedPhoto(val bytes: ByteArray, val contentType: String, val sha: String, val dhash: Long)
}
