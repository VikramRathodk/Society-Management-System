package org.devvikram.societymanagement.user.service

import org.devvikram.societymanagement.authorization.model.RoleScope
import org.devvikram.societymanagement.authorization.repository.RoleRepository
import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.repository.SocietyRepository
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.model.UserRole
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Validates the shape of users and role assignments. *Who* may create/assign what is decided
// by the caller (Access.canGrant / canManage in the controllers), not here.
@Service
class UserService(
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val roleRepository: RoleRepository,
    private val societyRepository: SocietyRepository,
    private val flatRepository: FlatRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun createUser(command: CreateUserCommand): User {
        require(userRepository.findByPhone(command.phone) == null) {
            "Phone number ${command.phone} is already in use"
        }
        val user = userRepository.save(
            User(
                name = command.name,
                phone = command.phone,
                email = command.email,
                passwordHash = passwordEncoder.encode(command.rawPassword)!!,
            )
        )
        assignRole(user.id!!, command.role)
        return user
    }

    @Transactional
    fun assignRole(userId: Long, command: AssignRoleCommand): UserRole {
        val user = getById(userId)
        val role = roleRepository.findByCode(command.roleCode)
            ?: throw NoSuchElementException("Role ${command.roleCode} not found")

        when (role.scopeLevel) {
            RoleScope.PLATFORM -> require(command.societyId == null && command.flatId == null) {
                "Role ${role.code} is platform-wide; societyId and flatId must be empty"
            }
            RoleScope.SOCIETY -> requireNotNull(command.societyId) { "Role ${role.code} needs a societyId" }
        }
        if (role.requiresFlat) {
            requireNotNull(command.flatId) { "Role ${role.code} needs a flatId" }
        } else {
            require(command.flatId == null && command.residentType == null) {
                "flatId and residentType only apply to roles tied to a flat"
            }
        }

        val society = command.societyId?.let {
            societyRepository.findById(it) ?: throw NoSuchElementException("Society $it not found")
        }
        val flat = command.flatId?.let {
            flatRepository.findById(it) ?: throw NoSuchElementException("Flat $it not found")
        }
        if (flat != null) {
            require(flat.wing.society.id == society?.id) { "Flat ${flat.flatNumber} does not belong to this society" }
        }
        require(
            userRoleRepository.findByUserId(userId).none {
                it.role.code == role.code && it.society?.id == society?.id && it.flat?.id == flat?.id
            }
        ) { "User already holds ${role.code} in this scope" }

        return userRoleRepository.save(
            UserRole(user = user, role = role, society = society, flat = flat, residentType = command.residentType)
        )
    }

    @Transactional
    fun removeRole(userId: Long, assignmentId: Long) {
        userRoleRepository.delete(getRoleAssignment(userId, assignmentId))
    }

    fun getRoleAssignment(userId: Long, assignmentId: Long): UserRole =
        userRoleRepository.findById(assignmentId)?.takeIf { it.user.id == userId }
            ?: throw NoSuchElementException("Role assignment $assignmentId not found for user $userId")

    fun getRoles(userId: Long): List<UserRole> = userRoleRepository.findByUserId(userId)

    fun getRolesByUser(userIds: Collection<Long>): Map<Long, List<UserRole>> =
        userRoleRepository.findByUserIds(userIds).groupBy { it.user.id!! }

    fun getById(id: Long): User =
        userRepository.findById(id) ?: throw NoSuchElementException("User $id not found")

    fun getByPhone(phone: String): User? = userRepository.findByPhone(phone)

    fun getAll(): List<User> = userRepository.findAll()

    // Users holding any role in one of these societies.
    fun getAllInSocieties(societyIds: Collection<Long>): List<User> =
        userRepository.findAllById(userRoleRepository.findUserIdsBySocietyIds(societyIds))

    @Transactional
    fun updateProfile(id: Long, command: UpdateProfileCommand): User {
        // A blank token clears it (logout). A token belongs to one device, so it is first taken off
        // any other account, e.g. a shared phone that switched users. Done before loading the user:
        // the bulk update clears the persistence context.
        val token = command.fcmToken?.trim()
        if (!token.isNullOrEmpty()) userRepository.clearFcmTokens(listOf(token), exceptUserId = id)
        val user = getById(id)
        command.name?.let { user.name = it }
        command.email?.let { user.email = it }
        token?.let { user.fcmToken = it.ifEmpty { null } }
        return userRepository.save(user)
    }

    // Tokens already issued stay valid until they expire: JWTs are stateless and carry no password version.
    @Transactional
    fun changePassword(id: Long, currentPassword: String, newPassword: String) {
        val user = getById(id)
        require(passwordEncoder.matches(currentPassword, user.passwordHash)) { "Current password is incorrect" }
        require(newPassword != currentPassword) { "New password must be different from the current one" }
        user.passwordHash = passwordEncoder.encode(newPassword)!!
        userRepository.save(user)
    }

    @Transactional
    fun deactivate(id: Long): User {
        val user = getById(id)
        user.isActive = false
        return userRepository.save(user)
    }

    // Permanent. Role assignments go with the user (ON DELETE CASCADE); anything that records what
    // they did — visitors logged or answered, notices, complaints — blocks it: deactivate instead.
    @Transactional
    fun delete(id: Long) {
        val user = getById(id)
        try {
            userRepository.delete(user)
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException("${user.name} has visitor, notice or complaint history; deactivate them instead")
        }
    }

    @Transactional
    fun activate(id: Long): User {
        val user = getById(id)
        user.isActive = true
        return userRepository.save(user)
    }
}
