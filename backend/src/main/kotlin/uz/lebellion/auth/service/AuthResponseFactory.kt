package uz.lebellion.auth.service

import org.springframework.stereotype.Component
import uz.lebellion.auth.domain.AppUser
import uz.lebellion.auth.web.AuthResponse
import uz.lebellion.auth.web.UserProfile

/** Builds the API auth response / user profile from a domain user + issued tokens. Shared by all flows. */
@Component
class AuthResponseFactory {

    fun authResponse(issued: TokenIssuer.Issued, user: AppUser) = AuthResponse(
        accessToken = issued.accessToken,
        refreshToken = issued.refreshToken,
        tokenType = "Bearer",
        expiresIn = issued.expiresInSeconds,
        user = profile(user),
    )

    fun profile(user: AppUser) = UserProfile(
        id = user.id!!,
        organizationId = user.organizationId,
        fullName = user.name,
        role = user.role,
        active = user.isActive,
        email = user.email,
        phone = user.phone,
        branchId = user.branchId,
        unitId = user.unitId,
        mustChangePassword = user.mustChangePassword,
        canScore = user.canScore,
        canReview = user.canReview,
    )
}
