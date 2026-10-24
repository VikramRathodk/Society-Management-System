package org.devvikram.societymanagement.auth.application

import org.devvikram.societymanagement.security.Access
import org.devvikram.societymanagement.security.AccessService
import org.devvikram.societymanagement.security.JwtService
import org.devvikram.societymanagement.security.toGrant
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.AppUserRepository
import org.devvikram.societymanagement.user.domain.UserRole
import org.devvikram.societymanagement.user.domain.UserRoleRepository
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.stereotype.Service

data class LoginResult(
    val token: String,
    val user: AppUser,
    val roles: List<UserRole>,
    val access: Access,
)

@Service
class AuthService(
    private val authenticationManager: AuthenticationManager,
    private val appUserRepository: AppUserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val jwtService: JwtService,
    private val accessService: AccessService,
) {
    fun login(phone: String, password: String): LoginResult {
        // Throws BadCredentialsException on mismatch/unknown user, DisabledException if deactivated.
        authenticationManager.authenticate(UsernamePasswordAuthenticationToken(phone, password))
        val user = appUserRepository.findByPhone(phone)
            ?: throw NoSuchElementException("User $phone not found")
        val roles = userRoleRepository.findByUserId(user.id!!)
        return LoginResult(
            token = jwtService.generateToken(user),
            user = user,
            roles = roles,
            access = accessService.accessFor(user.id!!, roles.map { it.toGrant() }),
        )
    }
}
