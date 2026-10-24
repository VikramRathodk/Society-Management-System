package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.Valid
import org.devvikram.societymanagement.security.RequiresPermission
import org.devvikram.societymanagement.society.application.FlatService
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
class FlatController(private val flatService: FlatService) {

    @PostMapping("/api/wings/{wingId}/flats")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("FLAT.STRUCTURE.CREATE")
    fun addFlat(@PathVariable wingId: UUID, @Valid @RequestBody request: CreateFlatRequest): FlatResponse =
        FlatResponse.from(flatService.addFlat(wingId, request.flatNumber))

    @GetMapping("/api/wings/{wingId}/flats")
    @RequiresPermission("FLAT.DIRECTORY.VIEW")
    fun listByWing(@PathVariable wingId: UUID): List<FlatResponse> =
        flatService.listByWing(wingId).map(FlatResponse::from)

    @GetMapping("/api/flats/{id}")
    @RequiresPermission("FLAT.DIRECTORY.VIEW")
    fun getById(@PathVariable id: UUID): FlatResponse = FlatResponse.from(flatService.getById(id))

    @PatchMapping("/api/flats/{id}")
    @RequiresPermission("FLAT.STRUCTURE.UPDATE")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateFlatRequest): FlatResponse =
        FlatResponse.from(flatService.updateFlatNumber(id, request.flatNumber))

    @DeleteMapping("/api/flats/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("FLAT.STRUCTURE.DELETE")
    fun delete(@PathVariable id: UUID) = flatService.deleteFlat(id)
}
