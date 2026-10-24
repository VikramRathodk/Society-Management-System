package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.Valid
import org.devvikram.societymanagement.security.AccessService
import org.devvikram.societymanagement.security.RequiresPermission
import org.devvikram.societymanagement.society.application.SocietyService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

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
    @RequiresPermission("SOCIETY.SETTINGS.CREATE")
    fun create(@Valid @RequestBody request: CreateSocietyRequest): SocietyResponse {
        accessService.current().requireOnPlatform("SOCIETY.SETTINGS.CREATE")
        return SocietyResponse.from(societyService.createSociety(request.name, request.address, request.complexId))
    }

    @GetMapping("/{id}")
    @RequiresPermission("SOCIETY.SETTINGS.VIEW")
    fun getById(@PathVariable id: UUID): SocietyResponse {
        accessService.current().requireIn("SOCIETY.SETTINGS.VIEW", id)
        return SocietyResponse.from(societyService.getById(id))
    }

    @GetMapping
    @RequiresPermission("SOCIETY.SETTINGS.VIEW")
    fun getAll(): List<SocietyResponse> {
        val visible = accessService.current().societiesWith("SOCIETY.SETTINGS.VIEW")
        return societyService.getAll()
            .filter { visible == null || it.id in visible }
            .map(SocietyResponse::from)
    }

    @PatchMapping("/{id}")
    @RequiresPermission("SOCIETY.SETTINGS.UPDATE")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateSocietyRequest): SocietyResponse {
        accessService.current().requireIn("SOCIETY.SETTINGS.UPDATE", id)
        return SocietyResponse.from(societyService.updateSettings(id, request.name, request.address))
    }
}
