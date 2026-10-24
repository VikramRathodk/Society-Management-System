package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.user.domain.AppUserRepository
import org.devvikram.societymanagement.user.domain.UserRole
import org.devvikram.societymanagement.user.domain.UserRoleRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

// "Username" is the app_user.phone column — there is no self-signup/separate username field.
@Service
class UserDetailsServiceImpl(
    private val appUserRepository: AppUserRepository,
    private val userRoleRepository: UserRoleRepository,
) : UserDetailsService {
    override fun loadUserByUsername(phone: String): UserDetails {
        val user = appUserRepository.findByPhone(phone)
            ?: throw UsernameNotFoundException("No user with phone $phone")
        return AuthenticatedUser(
            id = user.id!!,
            phone = user.phone,
            passwordHash = user.passwordHash,
            active = user.isActive,
            grants = userRoleRepository.findByUserId(user.id!!).map(UserRole::toGrant),
        )
    }
}

fun UserRole.toGrant() = RoleGrant(
    assignmentId = id!!,
    roleCode = role.code,
    societyId = society?.id,
    flatId = flat?.id,
)
