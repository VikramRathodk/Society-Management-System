package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.user.domain.AppUserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

// "Username" is the app_user.phone column — there is no self-signup/separate username field.
@Service
class UserDetailsServiceImpl(private val appUserRepository: AppUserRepository) : UserDetailsService {
    override fun loadUserByUsername(phone: String): UserDetails {
        val user = appUserRepository.findByPhone(phone)
            ?: throw UsernameNotFoundException("No user with phone $phone")
        return User.builder()
            .username(user.phone)
            .password(user.passwordHash)
            .authorities(SimpleGrantedAuthority("ROLE_${user.role.name}"))
            .disabled(!user.isActive)
            .build()
    }
}
