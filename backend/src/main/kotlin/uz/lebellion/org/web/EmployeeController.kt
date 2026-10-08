package uz.lebellion.org.web

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uz.lebellion.auth.security.AuthPrincipal
import uz.lebellion.auth.web.UserProfile
import uz.lebellion.common.web.Page
import uz.lebellion.org.service.EmployeeService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/employees")
class EmployeeController(private val employeeService: EmployeeService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) branchId: UUID?,
        @RequestParam(required = false) unitId: UUID?,
        @RequestParam(required = false) active: Boolean?,
    ): Page<UserProfile> = employeeService.list(principal, limit.coerceIn(1, 100), cursor, branchId, unitId, active)

    @PostMapping("/{employeeId}/deactivate")
    fun deactivate(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable employeeId: UUID,
    ): UserProfile = employeeService.deactivate(principal, employeeId)

    @PostMapping("/{employeeId}/recovery-invite")
    @ResponseStatus(HttpStatus.CREATED)
    fun recoveryInvite(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable employeeId: UUID,
        @Valid @RequestBody(required = false) req: RecoveryInviteRequest?,
    ): RecoveryInviteResponse = employeeService.issueRecoveryInvite(principal, employeeId, req ?: RecoveryInviteRequest())

    /** Grant/revoke can_score / can_review on an employee (FOUNDER only). */
    @PutMapping("/{employeeId}/permissions")
    fun setPermissions(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable employeeId: UUID,
        @Valid @RequestBody req: SetPermissionsRequest,
    ): UserProfile = employeeService.setPermissions(principal, employeeId, req)
}
