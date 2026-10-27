package org.devvikram.societymanagement.auth.service

import org.devvikram.societymanagement.authorization.AuthenticatedUser
import org.devvikram.societymanagement.user.model.UserRole
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

// "Username" is the users.phone column — there is no self-signup/separate username field.
@Service
class UserDetailsServiceImpl(
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
) : UserDetailsService {
    override fun loadUserByUsername(phone: String): UserDetails {
        val user = userRepository.findByPhone(phone)
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
