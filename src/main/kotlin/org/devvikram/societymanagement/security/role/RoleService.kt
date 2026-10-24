package org.devvikram.societymanagement.security.role

import org.devvikram.societymanagement.security.PermissionService
import org.devvikram.societymanagement.security.permission.Permission
import org.devvikram.societymanagement.security.permission.PermissionJpaRepository
import org.devvikram.societymanagement.user.domain.UserRoleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class CreateRoleCommand(
    val code: String,
    val name: String,
    val description: String? = null,
    val scopeLevel: RoleScope,
    val requiresFlat: Boolean = false,
    // Lower roles the new role includes (gets their permissions, may assign them).
    val inherits: Set<String> = emptySet(),
    // Higher roles that should include the new role — this places it in the hierarchy so
    // those roles can assign it.
    val includedIn: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet(),
)

// Every write evicts the permission caches, so changes apply on the next request.
@Service
class RoleService(
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionJpaRepository,
    private val userRoleRepository: UserRoleRepository,
    private val permissionService: PermissionService,
) {
    fun getAll(): List<Role> = roleRepository.findAll().sortedBy { it.code }

    fun getByCode(code: String): Role = roleRepository.findByCode(code) ?: throw NoSuchElementException("Role $code not found")

    fun getAllPermissions(): List<Permission> = permissionRepository.findAll().sortedBy { it.code }

    // Maps a role while its lazy inherits/permissions collections can still load (open-in-view is off).
    @Transactional(readOnly = true)
    fun <T> describe(code: String, mapper: (Role) -> T): T = mapper(getByCode(code))

    @Transactional
    fun create(command: CreateRoleCommand): Role {
        val code = command.code.trim().uppercase()
        require(CODE_PATTERN.matches(code)) { "Role code must be UPPER_SNAKE_CASE (letters, digits, underscores)" }
        require(roleRepository.findByCode(code) == null) { "Role $code already exists" }
        require(command.scopeLevel == RoleScope.SOCIETY || !command.requiresFlat) {
            "Only society roles can require a flat"
        }

        val role = roleRepository.save(
            Role(
                code = code,
                name = command.name,
                description = command.description,
                scopeLevel = command.scopeLevel,
                requiresFlat = command.requiresFlat,
            )
        )
        setInheritsInternal(role, command.inherits)
        setPermissionsInternal(role, command.permissions)
        resolveRoles(command.includedIn).forEach { parent ->
            requireEditable(parent)
            requireValidInheritance(parent, parent.inherits + role)
            parent.inherits.add(role)
        }
        permissionService.evictAll()
        return role
    }

    @Transactional
    fun update(code: String, name: String?, description: String?): Role {
        val role = getByCode(code)
        name?.let { role.name = it }
        description?.let { role.description = it }
        return role
    }

    @Transactional
    fun setPermissions(code: String, permissionCodes: Set<String>): Role {
        val role = getByCode(code)
        requireEditable(role)
        setPermissionsInternal(role, permissionCodes)
        permissionService.evictAll()
        return role
    }

    @Transactional
    fun setInherits(code: String, inheritedCodes: Set<String>): Role {
        val role = getByCode(code)
        requireEditable(role)
        setInheritsInternal(role, inheritedCodes)
        permissionService.evictAll()
        return role
    }

    @Transactional
    fun delete(code: String) {
        val role = getByCode(code)
        require(!role.isSystem) { "Built-in role $code cannot be deleted" }
        require(userRoleRepository.countByRoleId(role.id!!) == 0L) {
            "Role $code is still assigned to users; remove those assignments first"
        }
        // Detach from parents in this session too; the DB cascade alone would leave stale in-memory sets.
        roleRepository.findAll().forEach { it.inherits.remove(role) }
        roleRepository.delete(role)
        permissionService.evictAll()
    }

    private fun setInheritsInternal(role: Role, inheritedCodes: Set<String>) {
        val inherited = resolveRoles(inheritedCodes)
        requireValidInheritance(role, inherited)
        role.inherits.clear()
        role.inherits.addAll(inherited)
    }

    private fun setPermissionsInternal(role: Role, permissionCodes: Set<String>) {
        val permissions = permissionRepository.findByCodeIn(permissionCodes)
        val unknown = permissionCodes - permissions.map { it.code }.toSet()
        require(unknown.isEmpty()) { "Unknown permission codes: ${unknown.sorted()}" }
        role.permissions.clear()
        role.permissions.addAll(permissions)
    }

    private fun requireValidInheritance(role: Role, inherited: Collection<Role>) {
        // A society role inheriting a platform role would leak platform-wide powers into one society's scope.
        if (role.scopeLevel == RoleScope.SOCIETY) {
            require(inherited.all { it.scopeLevel == RoleScope.SOCIETY }) {
                "Society role ${role.code} cannot inherit platform roles"
            }
        }
        require(inherited.none { it.allPermissions }) { "No role can inherit an all-permissions role" }
        require(inherited.none { role in it.selfAndInherited() }) {
            "That would create a cycle in the role hierarchy"
        }
    }

    // All-permissions roles (SUPER_ADMIN) are managed automatically and cannot be locked out by an edit.
    private fun requireEditable(role: Role) {
        require(!role.allPermissions) { "Role ${role.code} has all permissions and cannot be edited" }
    }

    private fun resolveRoles(codes: Set<String>): List<Role> {
        val roles = roleRepository.findByCodes(codes)
        val unknown = codes - roles.map { it.code }.toSet()
        require(unknown.isEmpty()) { "Unknown role codes: ${unknown.sorted()}" }
        return roles
    }

    private companion object {
        val CODE_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,49}$")
    }
}
