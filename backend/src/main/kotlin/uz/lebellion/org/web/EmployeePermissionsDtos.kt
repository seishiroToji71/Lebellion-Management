package uz.lebellion.org.web

/** Grant/revoke the granular review & score permissions on an employee (full desired state). */
data class SetPermissionsRequest(
    val canScore: Boolean,
    val canReview: Boolean,
)
