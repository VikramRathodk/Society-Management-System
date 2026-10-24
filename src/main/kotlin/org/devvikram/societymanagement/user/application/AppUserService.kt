package org.devvikram.societymanagement.user.application

import org.devvikram.societymanagement.common.ConflictException
import org.devvikram.societymanagement.security.role.RoleRepository
import org.devvikram.societymanagement.security.role.RoleScope
import org.devvikram.societymanagement.society.domain.FlatRepository
import org.devvikram.societymanagement.society.domain.SocietyRepository
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.AppUserRepository
import org.devvikram.societymanagement.user.domain.UserRole
import org.devvikram.societymanagement.user.domain.UserRoleRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Validates the shape of users and role assignments. *Who* may create/assign what is decided
// by the caller (Access.canGrant / canManage in the controllers), not here.
@Service
class AppUserService(
    private val appUserRepository: AppUserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val roleRepository: RoleRepository,
    private val societyRepository: SocietyRepository,
    private val flatRepository: FlatRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun createUser(command: CreateAppUserCommand): AppUser {
        require(appUserRepository.findByPhone(command.phone) == null) {
            "Phone number ${command.phone} is already in use"
        }
        val appUser = appUserRepository.save(
            AppUser(
                name = command.name,
                phone = command.phone,
                email = command.email,
                passwordHash = passwordEncoder.encode(command.rawPassword)!!,
            )
        )
        assignRole(appUser.id!!, command.role)
        return appUser
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

    fun getById(id: Long): AppUser =
        appUserRepository.findById(id) ?: throw NoSuchElementException("User $id not found")

    fun getByPhone(phone: String): AppUser? = appUserRepository.findByPhone(phone)

    fun getAll(): List<AppUser> = appUserRepository.findAll()

    // Users holding any role in one of these societies.
    fun getAllInSocieties(societyIds: Collection<Long>): List<AppUser> =
        appUserRepository.findAllById(userRoleRepository.findUserIdsBySocietyIds(societyIds))

    @Transactional
    fun updateProfile(id: Long, command: UpdateProfileCommand): AppUser {
        val user = getById(id)
        command.name?.let { user.name = it }
        command.email?.let { user.email = it }
        command.fcmToken?.let { user.fcmToken = it }
        return appUserRepository.save(user)
    }

    @Transactional
    fun deactivate(id: Long): AppUser {
        val user = getById(id)
        user.isActive = false
        return appUserRepository.save(user)
    }

    // Permanent. Role assignments go with the user (ON DELETE CASCADE); anything that records what
    // they did — visitors logged or answered, notices, complaints — blocks it: deactivate instead.
    @Transactional
    fun delete(id: Long) {
        val user = getById(id)
        try {
            appUserRepository.delete(user)
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException("${user.name} has visitor, notice or complaint history; deactivate them instead")
        }
    }

    @Transactional
    fun activate(id: Long): AppUser {
        val user = getById(id)
        user.isActive = true
        return appUserRepository.save(user)
    }
}
