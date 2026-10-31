package org.devvikram.societymanagement.user.service

import org.devvikram.societymanagement.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

// A fresh database has no users, and every account is created by another user, so nobody could log in.
// On an empty users table this creates the first SUPER_ADMIN from app.bootstrap.admin.* (env vars
// BOOTSTRAP_ADMIN_*). Once any user exists it does nothing, so the variables can stay set or be removed.
@Component
class BootstrapAdmin(
    private val userRepository: UserRepository,
    private val userService: UserService,
    @Value("\${app.bootstrap.admin.name:Super Admin}") private val name: String,
    @Value("\${app.bootstrap.admin.phone:}") private val phone: String,
    @Value("\${app.bootstrap.admin.password:}") private val password: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (userRepository.count() > 0) return
        if (phone.isBlank() || password.isBlank()) {
            log.warn(
                "[bootstrap] no users exist and nobody can log in: set BOOTSTRAP_ADMIN_PHONE and " +
                    "BOOTSTRAP_ADMIN_PASSWORD (optionally BOOTSTRAP_ADMIN_NAME) and restart to create the first super admin"
            )
            return
        }
        // Same bounds as the password endpoints; BCrypt reads only the first 72 bytes.
        require(password.length in 8..72) { "BOOTSTRAP_ADMIN_PASSWORD must be 8-72 characters" }
        val user = userService.createUser(
            CreateUserCommand(
                name = name,
                phone = phone,
                rawPassword = password,
                role = AssignRoleCommand(roleCode = "SUPER_ADMIN"),
            )
        )
        log.info("[bootstrap] created super admin {} (id {}); change its password after the first login", phone, user.id)
    }
}
