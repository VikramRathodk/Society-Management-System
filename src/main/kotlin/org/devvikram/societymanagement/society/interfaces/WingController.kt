package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.Valid
import org.devvikram.societymanagement.security.RequiresPermission
import org.devvikram.societymanagement.society.application.WingService
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
import java.util.UUID

@RestController
class WingController(private val wingService: WingService) {

    @PostMapping("/api/societies/{societyId}/wings")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("FLAT.STRUCTURE.CREATE")
    fun addWing(@PathVariable societyId: UUID, @Valid @RequestBody request: CreateWingRequest): WingResponse =
        WingResponse.from(wingService.addWing(societyId, request.name))

    @GetMapping("/api/societies/{societyId}/wings")
    @RequiresPermission("FLAT.DIRECTORY.VIEW")
    fun listBySociety(@PathVariable societyId: UUID): List<WingResponse> =
        wingService.listBySociety(societyId).map(WingResponse::from)

    @GetMapping("/api/wings/{id}")
    @RequiresPermission("FLAT.DIRECTORY.VIEW")
    fun getById(@PathVariable id: UUID): WingResponse = WingResponse.from(wingService.getById(id))

    @PatchMapping("/api/wings/{id}")
    @RequiresPermission("FLAT.STRUCTURE.UPDATE")
    fun rename(@PathVariable id: UUID, @Valid @RequestBody request: RenameWingRequest): WingResponse =
        WingResponse.from(wingService.renameWing(id, request.name))

    @DeleteMapping("/api/wings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("FLAT.STRUCTURE.DELETE")
    fun delete(@PathVariable id: UUID) = wingService.deleteWing(id)
}
