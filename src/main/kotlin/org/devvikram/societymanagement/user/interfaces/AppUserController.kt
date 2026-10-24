package org.devvikram.societymanagement.user.interfaces

import jakarta.validation.Valid
import org.devvikram.societymanagement.security.AccessService
import org.devvikram.societymanagement.security.RequiresPermission
import org.devvikram.societymanagement.security.toGrant
import org.devvikram.societymanagement.user.application.AppUserService
import org.devvikram.societymanagement.user.application.CreateAppUserCommand
import org.devvikram.societymanagement.user.application.UpdateProfileCommand
import org.devvikram.societymanagement.user.domain.AppUser
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
class AppUserController(
    private val appUserService: AppUserService,
    private val accessService: AccessService,
) {

    @GetMapping("/me")
    fun me(): MeResponse {
        val access = accessService.current()
        return MeResponse(user = response(appUserService.getById(access.userId)), access = AccessResponse.from(access))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("USER.ACCOUNT.CREATE")
    fun create(@Valid @RequestBody request: CreateAppUserRequest): AppUserResponse {
        if (!accessService.current().canGrant("USER.ACCOUNT.CREATE", request.role, request.societyId)) {
            throw AccessDeniedException("You cannot create a ${request.role} in this scope")
        }
        val user = appUserService.createUser(
            CreateAppUserCommand(
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
    fun getById(@PathVariable id: Long): AppUserResponse = viewable(id, "USER.ACCOUNT.VIEW")

    // Everyone in the caller's societies (all users for a platform-level viewer); ?societyId narrows it.
    @GetMapping
    @RequiresPermission("USER.ACCOUNT.VIEW")
    fun getAll(@RequestParam(required = false) societyId: Long?): List<AppUserResponse> {
        val access = accessService.current()
        val visibleSocieties = access.societiesWith("USER.ACCOUNT.VIEW")
        val users = when {
            societyId != null -> {
                access.requireIn("USER.ACCOUNT.VIEW", societyId)
                appUserService.getAllInSocieties(listOf(societyId))
            }
            visibleSocieties == null -> appUserService.getAll()
            else -> appUserService.getAllInSocieties(visibleSocieties)
        }
        val rolesByUser = appUserService.getRolesByUser(users.map { it.id!! })
        return users.map { AppUserResponse.from(it, rolesByUser[it.id].orEmpty()) }
    }

    // USER.PROFILE.UPDATE lets a role edit its *own* profile; editing anyone else's needs
    // USER.ACCOUNT.UPDATE and a role above every role the target holds.
    @PatchMapping("/{id}")
    @RequiresPermission("USER.PROFILE.UPDATE")
    fun updateProfile(@PathVariable id: Long, @RequestBody request: UpdateProfileRequest): AppUserResponse {
        val access = accessService.current()
        if (access.userId != id) requireCanManage(id, "USER.ACCOUNT.UPDATE", "You can only update your own profile")
        val user = appUserService.updateProfile(
            id,
            UpdateProfileCommand(name = request.name, email = request.email, fcmToken = request.fcmToken),
        )
        return response(user)
    }

    @PatchMapping("/{id}/deactivate")
    @RequiresPermission("USER.ACCOUNT.DELETE")
    fun deactivate(@PathVariable id: Long): AppUserResponse {
        if (accessService.current().userId == id) throw IllegalArgumentException("You cannot deactivate yourself")
        requireCanManage(id, "USER.ACCOUNT.DELETE", "You cannot deactivate a user at or above your level")
        return response(appUserService.deactivate(id))
    }

    // Permanent removal, platform-only: society admins can deactivate but never erase a user. Refused
    // with 409 while the user has history (see AppUserService.delete).
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("USER.ACCOUNT.DELETE")
    fun delete(@PathVariable id: Long) {
        val access = accessService.current()
        if (access.userId == id) throw IllegalArgumentException("You cannot delete yourself")
        access.requireOnPlatform("USER.ACCOUNT.DELETE")
        requireCanManage(id, "USER.ACCOUNT.DELETE", "You cannot delete a user at or above your level")
        appUserService.delete(id)
    }

    // The undo of deactivate, so it takes the same permission and rank check.
    @PatchMapping("/{id}/activate")
    @RequiresPermission("USER.ACCOUNT.DELETE")
    fun activate(@PathVariable id: Long): AppUserResponse {
        requireCanManage(id, "USER.ACCOUNT.DELETE", "You cannot activate a user at or above your level")
        return response(appUserService.activate(id))
    }

    @GetMapping("/{id}/roles")
    @RequiresPermission("USER.ROLE.VIEW")
    fun getRoles(@PathVariable id: Long): List<RoleAssignmentResponse> = viewable(id, "USER.ROLE.VIEW").roles

    @PostMapping("/{id}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("USER.ROLE.ASSIGN")
    fun assignRole(@PathVariable id: Long, @Valid @RequestBody request: AssignRoleRequest): RoleAssignmentResponse {
        if (!accessService.current().canGrant("USER.ROLE.ASSIGN", request.role, request.societyId)) {
            throw AccessDeniedException("You cannot assign ${request.role} in this scope")
        }
        val assignment = appUserService.assignRole(id, request.toCommand())
        // Re-read with role/society/flat loaded for the response.
        return RoleAssignmentResponse.from(appUserService.getRoleAssignment(id, assignment.id!!))
    }

    @DeleteMapping("/{id}/roles/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("USER.ROLE.DELETE")
    fun removeRole(@PathVariable id: Long, @PathVariable assignmentId: Long) {
        val assignment = appUserService.getRoleAssignment(id, assignmentId)
        if (!accessService.current().canManage("USER.ROLE.DELETE", listOf(assignment.toGrant()))) {
            throw AccessDeniedException("You cannot remove a role at or above your level")
        }
        appUserService.removeRole(id, assignmentId)
    }

    // Viewing (unlike managing) needs no rank: the permission in any society the target belongs to.
    private fun viewable(id: Long, permission: String): AppUserResponse {
        val access = accessService.current()
        val user = appUserService.getById(id)
        val roles = appUserService.getRoles(id)
        val visible = access.canOnPlatform(permission) ||
            roles.any { r -> r.society?.id?.let { access.can(permission, it) } == true }
        if (!visible) throw AccessDeniedException("User is outside your societies")
        return AppUserResponse.from(user, roles)
    }

    private fun requireCanManage(targetId: Long, permission: String, message: String) {
        appUserService.getById(targetId) // 404 before 403 for unknown users
        val targetGrants = appUserService.getRoles(targetId).map { it.toGrant() }
        if (!accessService.current().canManage(permission, targetGrants)) throw AccessDeniedException(message)
    }

    private fun response(user: AppUser) = AppUserResponse.from(user, appUserService.getRoles(user.id!!))
}
