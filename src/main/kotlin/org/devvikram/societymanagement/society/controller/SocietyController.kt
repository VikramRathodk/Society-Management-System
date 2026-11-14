package org.devvikram.societymanagement.society.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.society.dto.CreateSocietyRequest
import org.devvikram.societymanagement.society.dto.SocietyResponse
import org.devvikram.societymanagement.society.dto.UpdateSocietyRequest
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.model.SocietyStatus
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.multipart.MultipartFile
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
        val status = if (request.startLive) SocietyStatus.LIVE else SocietyStatus.SETUP
        return SocietyResponse.from(societyService.createSociety(request.name.trim(), request.address, request.complexId, status))
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
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateSocietyRequest): SocietyResponse {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, id)
        return SocietyResponse.from(societyService.updateProfile(id, request.toCommand()))
    }

    // ---- Logo: JPEG, PNG or WebP, up to 1 MB ----

    @PutMapping("/{id}/logo")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun setLogo(@PathVariable id: Long, @RequestPart("file") file: MultipartFile): SocietyResponse {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, id)
        return SocietyResponse.from(societyService.setLogo(id, file.bytes))
    }

    @GetMapping("/{id}/logo")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_VIEW)
    fun logo(@PathVariable id: Long): ResponseEntity<ByteArray> {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_VIEW, id)
        val logo = societyService.logo(id) ?: throw NoSuchElementException("Society $id has no logo")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(logo.contentType))
            .cacheControl(CacheControl.noStore())
            .body(logo.data)
    }

    @DeleteMapping("/{id}/logo")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun removeLogo(@PathVariable id: Long): SocietyResponse {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, id)
        return SocietyResponse.from(societyService.removeLogo(id))
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
