package uz.lebellion.schedule.web

import uz.lebellion.schedule.domain.TaskStatus

/** 409 — a submission targets a CANCELLED task (schedule was edited/deactivated). The attempt is recorded. */
class TaskCancelledException : RuntimeException("This task was cancelled")

/** 409 — the task is not open for submission (e.g. already MISSED, SUBMITTED or DONE). */
class TaskNotOpenException(val status: TaskStatus) : RuntimeException("Task is not open for submission: $status")
