package uz.lebellion.kpi.domain

/** DRAFT reads sheet points live and is editable; FINALIZED is a frozen snapshot. */
enum class EvaluationStatus {
    DRAFT,
    FINALIZED,
}
