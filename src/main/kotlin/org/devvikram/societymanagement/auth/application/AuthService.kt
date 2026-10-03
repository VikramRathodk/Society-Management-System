package org.devvikram.societymanagement.auth.application

import org.devvikram.societymanagement.security.JwtService
import org.devvikram.societymanagement.security.PermissionService
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.AppUserRepository
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.stereotype.Service

data class LoginResult(
    val token: String,
    val user: AppUser,
    val permissions: Set<String>,
)

@Service
class AuthService(
    private val authenticationManager: AuthenticationManager,
    private val appUserRepository: AppUserRepository,
    private val jwtService: JwtService,
    private val permissionService: PermissionService,
) {
    fun login(phone: String, password: String): LoginResult {
        // Throws BadCredentialsException on mismatch/unknown user, DisabledException if deactivated.
        authenticationManager.authenticate(UsernamePasswordAuthenticationToken(phone, password))
        val user = appUserRepository.findByPhone(phone)
            ?: throw NoSuchElementException("User $phone not found")
        return LoginResult(
            token = jwtService.generateToken(user),
            user = user,
            permissions = permissionService.getPermissionsForRole(user.role),
        )
    }
}
