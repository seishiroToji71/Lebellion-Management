package uz.lebellion.kpi.web

/** 409 — the evaluation is FINALIZED (a frozen snapshot) and cannot be changed. */
class EvaluationFinalizedException : RuntimeException("Evaluation is finalized")

/** 400 — the awarded points are not one of the allowed values {0, points/2, points} for the item. */
class InvalidAwardException(message: String) : RuntimeException(message)
