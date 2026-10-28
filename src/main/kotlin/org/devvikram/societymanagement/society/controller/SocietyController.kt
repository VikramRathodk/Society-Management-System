package org.devvikram.societymanagement.society.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.society.dto.CreateSocietyRequest
import org.devvikram.societymanagement.society.dto.SocietyResponse
import org.devvikram.societymanagement.society.dto.UpdateSocietyRequest
import org.devvikram.societymanagement.society.service.SocietyService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Creating a society is platform-level (SUPER_ADMIN); everything else is checked per society,
// so an ADMIN/SECRETARY only sees and edits the societies they hold a role in.
@RestController
@RequestMapping("/api/societies")
class SocietyController(
    private val societyService: SocietyService,
    private val accessService: AccessService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_CREATE)
    fun create(@Valid @RequestBody request: CreateSocietyRequest): SocietyResponse {
        accessService.current().requireOnPlatform(Permissions.SOCIETY_SETTINGS_CREATE)
        return SocietyResponse.from(societyService.createSociety(request.name, request.address, request.complexId))
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_VIEW)
    fun getById(@PathVariable id: Long): SocietyResponse {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_VIEW, id)
        return SocietyResponse.from(societyService.getById(id))
    }

    @GetMapping
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_VIEW)
    fun getAll(): List<SocietyResponse> {
        val visible = accessService.current().societiesWith(Permissions.SOCIETY_SETTINGS_VIEW)
        return societyService.getAll()
            .filter { visible == null || it.id in visible }
            .map(SocietyResponse::from)
    }

    @PatchMapping("/{id}")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun update(@PathVariable id: Long, @RequestBody request: UpdateSocietyRequest): SocietyResponse {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, id)
        return SocietyResponse.from(societyService.updateSettings(id, request.name, request.address))
    }

    // Platform-only, like create. Refused with 409 while the society has history (see SocietyService.delete).
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_DELETE)
    fun delete(@PathVariable id: Long) {
        accessService.current().requireOnPlatform(Permissions.SOCIETY_SETTINGS_DELETE)
        societyService.delete(id)
    }
}
