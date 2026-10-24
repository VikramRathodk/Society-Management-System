package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.Valid
import org.devvikram.societymanagement.security.AccessService
import org.devvikram.societymanagement.security.RequiresPermission
import org.devvikram.societymanagement.society.application.ComplexService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// COMPLEX.SETTINGS.* are seeded but mapped to no role (reserved until a Complex Admin role exists),
// so these endpoints return 403 for everyone until role_permission rows are added.
@RestController
@RequestMapping("/api/complexes")
class ComplexController(
    private val complexService: ComplexService,
    private val accessService: AccessService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("COMPLEX.SETTINGS.UPDATE")
    fun create(@Valid @RequestBody request: CreateComplexRequest): ComplexResponse {
        accessService.current().requireOnPlatform("COMPLEX.SETTINGS.UPDATE")
        return ComplexResponse.from(complexService.createComplex(request.name, request.address))
    }

    @GetMapping("/{id}")
    @RequiresPermission("COMPLEX.SETTINGS.VIEW")
    fun getById(@PathVariable id: Long): ComplexResponse {
        accessService.current().requireOnPlatform("COMPLEX.SETTINGS.VIEW")
        return ComplexResponse.from(complexService.getById(id))
    }

    @GetMapping
    @RequiresPermission("COMPLEX.SETTINGS.VIEW")
    fun getAll(): List<ComplexResponse> {
        accessService.current().requireOnPlatform("COMPLEX.SETTINGS.VIEW")
        return complexService.getAll().map(ComplexResponse::from)
    }

    @PatchMapping("/{id}")
    @RequiresPermission("COMPLEX.SETTINGS.UPDATE")
    fun update(@PathVariable id: Long, @RequestBody request: UpdateComplexRequest): ComplexResponse {
        accessService.current().requireOnPlatform("COMPLEX.SETTINGS.UPDATE")
        return ComplexResponse.from(complexService.updateSettings(id, request.name, request.address))
    }
}
