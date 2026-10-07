package uz.lebellion.submission.web

import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.submission.service.PhotoSubmissionService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/task-instances/{taskInstanceId}")
class PhotoSubmissionController(private val submissionService: PhotoSubmissionService) {

    /**
     * Submit a task (multipart): `answer` Yes/No, optional `photo` (required when the task needs one),
     * optional repeated `helperUserIds`. Server time is authoritative; a MISSED task may be submitted
     * late within the window (flagged, status preserved). 409 DUPLICATE_PHOTO / TASK_CANCELLED /
     * TASK_NOT_OPEN / LATE_WINDOW_CLOSED; 415 unsupported image; 413 too large.
     */
    @PostMapping("/submit", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submit(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable taskInstanceId: UUID,
        @RequestParam("answer") answer: Boolean,
        @RequestParam(name = "helperUserIds", required = false) helperUserIds: List<UUID>?,
        @RequestParam(name = "photo", required = false) photo: MultipartFile?,
    ): SubmissionResponse = submissionService.submit(
        principal,
        taskInstanceId,
        answer,
        helperUserIds ?: emptyList(),
        photo?.takeIf { !it.isEmpty }?.bytes,
    )
}
