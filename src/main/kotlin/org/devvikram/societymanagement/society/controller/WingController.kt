package org.devvikram.societymanagement.society.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.society.dto.CreateWingRequest
import org.devvikram.societymanagement.society.dto.RenameWingRequest
import org.devvikram.societymanagement.society.dto.WingResponse
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.WingService
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Every action is checked against the society the wing belongs to. Society-wide roles see every wing;
// flat-tied roles (RESIDENT) see only the wings their own flats are in.
@RestController
class WingController(
    private val wingService: WingService,
    private val flatService: FlatService,
    private val accessService: AccessService,
) {

    @PostMapping("/api/societies/{societyId}/wings")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FLAT_STRUCTURE_CREATE)
    fun addWing(@PathVariable societyId: Long, @Valid @RequestBody request: CreateWingRequest): WingResponse {
        accessService.current().requireIn(Permissions.FLAT_STRUCTURE_CREATE, societyId)
        return WingResponse.from(wingService.addWing(societyId, request.name))
    }

    @GetMapping("/api/societies/{societyId}/wings")
    @RequiresPermission(Permissions.FLAT_DIRECTORY_VIEW)
    fun listBySociety(@PathVariable societyId: Long): List<WingResponse> {
        val access = accessService.current()
        access.requireIn(Permissions.FLAT_DIRECTORY_VIEW, societyId)
        val wings = wingService.listBySociety(societyId)
        if (access.canSocietyWide(Permissions.FLAT_DIRECTORY_VIEW, societyId)) return wings.map(WingResponse::from)
        val own = ownWingIds(access)
        return wings.filter { it.id in own }.map(WingResponse::from)
    }

    @GetMapping("/api/wings/{id}")
    @RequiresPermission(Permissions.FLAT_DIRECTORY_VIEW)
    fun getById(@PathVariable id: Long): WingResponse {
        val access = accessService.current()
        val societyId = wingService.societyIdOf(id)
        access.requireIn(Permissions.FLAT_DIRECTORY_VIEW, societyId)
        if (!access.canSocietyWide(Permissions.FLAT_DIRECTORY_VIEW, societyId) && id !in ownWingIds(access)) {
            throw AccessDeniedException("You can only see your own wing")
        }
        return WingResponse.from(wingService.getById(id))
    }

    @PatchMapping("/api/wings/{id}")
    @RequiresPermission(Permissions.FLAT_STRUCTURE_UPDATE)
    fun rename(@PathVariable id: Long, @Valid @RequestBody request: RenameWingRequest): WingResponse {
        accessService.current().requireIn(Permissions.FLAT_STRUCTURE_UPDATE, wingService.societyIdOf(id))
        return WingResponse.from(wingService.renameWing(id, request.name))
    }

    @DeleteMapping("/api/wings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.FLAT_STRUCTURE_DELETE)
    fun delete(@PathVariable id: Long) {
        accessService.current().requireIn(Permissions.FLAT_STRUCTURE_DELETE, wingService.societyIdOf(id))
        wingService.deleteWing(id)
    }

    private fun ownWingIds(access: Access): Set<Long> =
        access.flatsWith(Permissions.FLAT_DIRECTORY_VIEW).map { flatService.getById(it).wing.id!! }.toSet()
}
