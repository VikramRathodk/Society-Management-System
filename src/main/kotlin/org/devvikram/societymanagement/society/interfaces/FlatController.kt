package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.Valid
import org.devvikram.societymanagement.security.AccessService
import org.devvikram.societymanagement.security.RequiresPermission
import org.devvikram.societymanagement.society.application.FlatService
import org.devvikram.societymanagement.society.application.WingService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Every action is checked against the society the flat (or its wing) belongs to.
@RestController
class FlatController(
    private val flatService: FlatService,
    private val wingService: WingService,
    private val accessService: AccessService,
) {

    @PostMapping("/api/wings/{wingId}/flats")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("FLAT.STRUCTURE.CREATE")
    fun addFlat(@PathVariable wingId: Long, @Valid @RequestBody request: CreateFlatRequest): FlatResponse {
        accessService.current().requireIn("FLAT.STRUCTURE.CREATE", wingService.societyIdOf(wingId))
        return FlatResponse.from(flatService.addFlat(wingId, request.flatNumber))
    }

    @GetMapping("/api/wings/{wingId}/flats")
    @RequiresPermission("FLAT.DIRECTORY.VIEW")
    fun listByWing(@PathVariable wingId: Long): List<FlatResponse> {
        accessService.current().requireIn("FLAT.DIRECTORY.VIEW", wingService.societyIdOf(wingId))
        return flatService.listByWing(wingId).map(FlatResponse::from)
    }

    @GetMapping("/api/flats/{id}")
    @RequiresPermission("FLAT.DIRECTORY.VIEW")
    fun getById(@PathVariable id: Long): FlatResponse {
        accessService.current().requireIn("FLAT.DIRECTORY.VIEW", flatService.societyIdOf(id))
        return FlatResponse.from(flatService.getById(id))
    }

    @PatchMapping("/api/flats/{id}")
    @RequiresPermission("FLAT.STRUCTURE.UPDATE")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateFlatRequest): FlatResponse {
        accessService.current().requireIn("FLAT.STRUCTURE.UPDATE", flatService.societyIdOf(id))
        return FlatResponse.from(flatService.updateFlatNumber(id, request.flatNumber))
    }

    @DeleteMapping("/api/flats/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("FLAT.STRUCTURE.DELETE")
    fun delete(@PathVariable id: Long) {
        accessService.current().requireIn("FLAT.STRUCTURE.DELETE", flatService.societyIdOf(id))
        flatService.deleteFlat(id)
    }
}
