package org.devvikram.societymanagement.user.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.user.dto.AccessResponse
import org.devvikram.societymanagement.user.dto.AssignRoleRequest
import org.devvikram.societymanagement.user.dto.CreateUserRequest
import org.devvikram.societymanagement.user.dto.MeResponse
import org.devvikram.societymanagement.user.dto.RoleAssignmentResponse
import org.devvikram.societymanagement.user.dto.UpdateProfileRequest
import org.devvikram.societymanagement.user.dto.UserResponse
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UpdateProfileCommand
import org.devvikram.societymanagement.user.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// @RequiresPermission is the coarse gate (permission held somewhere); each handler then checks
// the specific society and the role hierarchy: higher roles manage only roles below them.
@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val accessService: AccessService,
) {

    @GetMapping("/me")
    fun me(): MeResponse {
        val access = accessService.current()
        return MeResponse(user = response(userService.getById(access.userId)), access = AccessResponse.from(access))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("USER.ACCOUNT.CREATE")
    fun create(@Valid @RequestBody request: CreateUserRequest): UserResponse {
        if (!accessService.current().canGrant("USER.ACCOUNT.CREATE", request.role, request.societyId)) {
            throw AccessDeniedException("You cannot create a ${request.role} in this scope")
        }
        val user = userService.createUser(
            CreateUserCommand(
                name = request.name,
                phone = request.phone,
                email = request.email,
                rawPassword = request.password,
                role = request.roleCommand(),
            )
        )
        return response(user)
    }

    @GetMapping("/{id}")
    @RequiresPermission("USER.ACCOUNT.VIEW")
    fun getById(@PathVariable id: Long): UserResponse = viewable(id, "USER.ACCOUNT.VIEW")

    // Everyone in the caller's societies (all users for a platform-level viewer); ?societyId narrows it.
    @GetMapping
    @RequiresPermission("USER.ACCOUNT.VIEW")
    fun getAll(@RequestParam(required = false) societyId: Long?): List<UserResponse> {
        val access = accessService.current()
        val visibleSocieties = access.societiesWith("USER.ACCOUNT.VIEW")
        val users = when {
            societyId != null -> {
                access.requireIn("USER.ACCOUNT.VIEW", societyId)
                userService.getAllInSocieties(listOf(societyId))
            }
            visibleSocieties == null -> userService.getAll()
            else -> userService.getAllInSocieties(visibleSocieties)
        }
        val rolesByUser = userService.getRolesByUser(users.map { it.id!! })
        return users.map { UserResponse.from(it, rolesByUser[it.id].orEmpty()) }
    }

    // USER.PROFILE.UPDATE lets a role edit its *own* profile; editing anyone else's needs
    // USER.ACCOUNT.UPDATE and a role above every role the target holds.
    @PatchMapping("/{id}")
    @RequiresPermission("USER.PROFILE.UPDATE")
    fun updateProfile(@PathVariable id: Long, @RequestBody request: UpdateProfileRequest): UserResponse {
        val access = accessService.current()
        if (access.userId != id) requireCanManage(id, "USER.ACCOUNT.UPDATE", "You can only update your own profile")
        val user = userService.updateProfile(
            id,
            UpdateProfileCommand(name = request.name, email = request.email, fcmToken = request.fcmToken),
        )
        return response(user)
    }

    @PatchMapping("/{id}/deactivate")
    @RequiresPermission("USER.ACCOUNT.DELETE")
    fun deactivate(@PathVariable id: Long): UserResponse {
        if (accessService.current().userId == id) throw IllegalArgumentException("You cannot deactivate yourself")
        requireCanManage(id, "USER.ACCOUNT.DELETE", "You cannot deactivate a user at or above your level")
        return response(userService.deactivate(id))
    }

    // Permanent removal, platform-only: society admins can deactivate but never erase a user. Refused
    // with 409 while the user has history (see UserService.delete).
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("USER.ACCOUNT.DELETE")
    fun delete(@PathVariable id: Long) {
        val access = accessService.current()
        if (access.userId == id) throw IllegalArgumentException("You cannot delete yourself")
        access.requireOnPlatform("USER.ACCOUNT.DELETE")
        requireCanManage(id, "USER.ACCOUNT.DELETE", "You cannot delete a user at or above your level")
        userService.delete(id)
    }

    // The undo of deactivate, so it takes the same permission and rank check.
    @PatchMapping("/{id}/activate")
    @RequiresPermission("USER.ACCOUNT.DELETE")
    fun activate(@PathVariable id: Long): UserResponse {
        requireCanManage(id, "USER.ACCOUNT.DELETE", "You cannot activate a user at or above your level")
        return response(userService.activate(id))
    }

    @GetMapping("/{id}/roles")
    @RequiresPermission("USER.ROLE.VIEW")
    fun getRoles(@PathVariable id: Long): List<RoleAssignmentResponse> = viewable(id, "USER.ROLE.VIEW").roles

    // Effective permissions of another user, resolved the same way as /me (inheritance, all_permissions).
    @GetMapping("/{id}/permissions")
    @RequiresPermission("USER.ROLE.VIEW")
    fun getPermissions(@PathVariable id: Long): AccessResponse {
        viewable(id, "USER.ROLE.VIEW")
        val grants = userService.getRoles(id).map { it.toGrant() }
        return AccessResponse.from(accessService.accessFor(id, grants))
    }

    @PostMapping("/{id}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("USER.ROLE.ASSIGN")
    fun assignRole(@PathVariable id: Long, @Valid @RequestBody request: AssignRoleRequest): RoleAssignmentResponse {
        if (!accessService.current().canGrant("USER.ROLE.ASSIGN", request.role, request.societyId)) {
            throw AccessDeniedException("You cannot assign ${request.role} in this scope")
        }
        val assignment = userService.assignRole(id, request.toCommand())
        // Re-read with role/society/flat loaded for the response.
        return RoleAssignmentResponse.from(userService.getRoleAssignment(id, assignment.id!!))
    }

    @DeleteMapping("/{id}/roles/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("USER.ROLE.DELETE")
    fun removeRole(@PathVariable id: Long, @PathVariable assignmentId: Long) {
        val assignment = userService.getRoleAssignment(id, assignmentId)
        if (!accessService.current().canManage("USER.ROLE.DELETE", listOf(assignment.toGrant()))) {
            throw AccessDeniedException("You cannot remove a role at or above your level")
        }
        userService.removeRole(id, assignmentId)
    }

    // Viewing (unlike managing) needs no rank: the permission in any society the target belongs to.
    private fun viewable(id: Long, permission: String): UserResponse {
        val access = accessService.current()
        val user = userService.getById(id)
        val roles = userService.getRoles(id)
        val visible = access.canOnPlatform(permission) ||
            roles.any { r -> r.society?.id?.let { access.can(permission, it) } == true }
        if (!visible) throw AccessDeniedException("User is outside your societies")
        return UserResponse.from(user, roles)
    }

    private fun requireCanManage(targetId: Long, permission: String, message: String) {
        userService.getById(targetId) // 404 before 403 for unknown users
        val targetGrants = userService.getRoles(targetId).map { it.toGrant() }
        if (!accessService.current().canManage(permission, targetGrants)) throw AccessDeniedException(message)
    }

    private fun response(user: User) = UserResponse.from(user, userService.getRoles(user.id!!))
}
