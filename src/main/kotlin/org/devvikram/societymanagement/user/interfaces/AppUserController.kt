package org.devvikram.societymanagement.user.interfaces

import jakarta.validation.Valid
import org.devvikram.societymanagement.security.PermissionService
import org.devvikram.societymanagement.security.RequiresPermission
import org.devvikram.societymanagement.user.application.AppUserService
import org.devvikram.societymanagement.user.application.CreateAppUserCommand
import org.devvikram.societymanagement.user.application.UpdateProfileCommand
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/users")
class AppUserController(
    private val appUserService: AppUserService,
    private val permissionService: PermissionService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("USER.ACCOUNT.CREATE")
    fun create(@Valid @RequestBody request: CreateAppUserRequest): AppUserResponse {
        val user = appUserService.createUser(
            CreateAppUserCommand(
                name = request.name,
                phone = request.phone,
                email = request.email,
                rawPassword = request.password,
                role = request.role,
                societyId = request.societyId,
                complexId = request.complexId,
                flatId = request.flatId,
                residentType = request.residentType,
            )
        )
        return AppUserResponse.from(user)
    }

    @GetMapping("/{id}")
    @RequiresPermission("USER.ACCOUNT.VIEW")
    fun getById(@PathVariable id: UUID): AppUserResponse = AppUserResponse.from(appUserService.getById(id))

    @GetMapping
    @RequiresPermission("USER.ACCOUNT.VIEW")
    fun getAll(): List<AppUserResponse> = appUserService.getAll().map(AppUserResponse::from)

    // USER.PROFILE.UPDATE lets a role edit its *own* profile (permission matrix, society_planning.md);
    // editing anyone else's additionally needs USER.ACCOUNT.UPDATE.
    @PatchMapping("/{id}")
    @RequiresPermission("USER.PROFILE.UPDATE")
    fun updateProfile(
        @PathVariable id: UUID,
        @RequestBody request: UpdateProfileRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): AppUserResponse {
        val currentUser = appUserService.getByPhone(principal.username)
            ?: throw AccessDeniedException("Current user no longer exists")
        if (currentUser.id != id && "USER.ACCOUNT.UPDATE" !in permissionService.getPermissionsForRole(currentUser.role)) {
            throw AccessDeniedException("You can only update your own profile")
        }
        val user = appUserService.updateProfile(
            id,
            UpdateProfileCommand(name = request.name, email = request.email, fcmToken = request.fcmToken),
        )
        return AppUserResponse.from(user)
    }

    @PatchMapping("/{id}/deactivate")
    @RequiresPermission("USER.ACCOUNT.DELETE")
    fun deactivate(@PathVariable id: UUID): AppUserResponse = AppUserResponse.from(appUserService.deactivate(id))
}
