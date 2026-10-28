package org.devvikram.societymanagement.society.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.society.dto.CreateFlatRequest
import org.devvikram.societymanagement.society.dto.FlatResponse
import org.devvikram.societymanagement.society.dto.UpdateFlatRequest
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

// Every action is checked against the society the flat (or its wing) belongs to. Society-wide roles see
// every flat; flat-tied roles (RESIDENT) see only their own.
@RestController
class FlatController(
    private val flatService: FlatService,
    private val wingService: WingService,
    private val accessService: AccessService,
) {

    @PostMapping("/api/wings/{wingId}/flats")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FLAT_STRUCTURE_CREATE)
    fun addFlat(@PathVariable wingId: Long, @Valid @RequestBody request: CreateFlatRequest): FlatResponse {
        accessService.current().requireIn(Permissions.FLAT_STRUCTURE_CREATE, wingService.societyIdOf(wingId))
        return FlatResponse.from(flatService.addFlat(wingId, request.flatNumber))
    }

    @GetMapping("/api/wings/{wingId}/flats")
    @RequiresPermission(Permissions.FLAT_DIRECTORY_VIEW)
    fun listByWing(@PathVariable wingId: Long): List<FlatResponse> {
        val access = accessService.current()
        val societyId = wingService.societyIdOf(wingId)
        access.requireIn(Permissions.FLAT_DIRECTORY_VIEW, societyId)
        val flats = flatService.listByWing(wingId)
        if (access.canSocietyWide(Permissions.FLAT_DIRECTORY_VIEW, societyId)) return flats.map(FlatResponse::from)
        val own = access.flatsWith(Permissions.FLAT_DIRECTORY_VIEW)
        return flats.filter { it.id in own }.map(FlatResponse::from)
    }

    @GetMapping("/api/flats/{id}")
    @RequiresPermission(Permissions.FLAT_DIRECTORY_VIEW)
    fun getById(@PathVariable id: Long): FlatResponse {
        val access = accessService.current()
        val societyId = flatService.societyIdOf(id)
        access.requireIn(Permissions.FLAT_DIRECTORY_VIEW, societyId)
        if (!access.canOnFlat(Permissions.FLAT_DIRECTORY_VIEW, societyId, id)) {
            throw AccessDeniedException("You can only see your own flat")
        }
        return FlatResponse.from(flatService.getById(id))
    }

    @PatchMapping("/api/flats/{id}")
    @RequiresPermission(Permissions.FLAT_STRUCTURE_UPDATE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateFlatRequest): FlatResponse {
        accessService.current().requireIn(Permissions.FLAT_STRUCTURE_UPDATE, flatService.societyIdOf(id))
        return FlatResponse.from(flatService.updateFlatNumber(id, request.flatNumber))
    }

    @DeleteMapping("/api/flats/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.FLAT_STRUCTURE_DELETE)
    fun delete(@PathVariable id: Long) {
        accessService.current().requireIn(Permissions.FLAT_STRUCTURE_DELETE, flatService.societyIdOf(id))
        flatService.deleteFlat(id)
    }
}
