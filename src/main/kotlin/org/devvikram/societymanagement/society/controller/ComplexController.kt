package org.devvikram.societymanagement.society.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.society.dto.ComplexResponse
import org.devvikram.societymanagement.society.dto.CreateComplexRequest
import org.devvikram.societymanagement.society.dto.UpdateComplexRequest
import org.devvikram.societymanagement.society.service.ComplexService
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
    @RequiresPermission(Permissions.COMPLEX_SETTINGS_UPDATE)
    fun create(@Valid @RequestBody request: CreateComplexRequest): ComplexResponse {
        accessService.current().requireOnPlatform(Permissions.COMPLEX_SETTINGS_UPDATE)
        return ComplexResponse.from(complexService.createComplex(request.name, request.address))
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.COMPLEX_SETTINGS_VIEW)
    fun getById(@PathVariable id: Long): ComplexResponse {
        accessService.current().requireOnPlatform(Permissions.COMPLEX_SETTINGS_VIEW)
        return ComplexResponse.from(complexService.getById(id))
    }

    @GetMapping
    @RequiresPermission(Permissions.COMPLEX_SETTINGS_VIEW)
    fun getAll(): List<ComplexResponse> {
        accessService.current().requireOnPlatform(Permissions.COMPLEX_SETTINGS_VIEW)
        return complexService.getAll().map(ComplexResponse::from)
    }

    @PatchMapping("/{id}")
    @RequiresPermission(Permissions.COMPLEX_SETTINGS_UPDATE)
    fun update(@PathVariable id: Long, @RequestBody request: UpdateComplexRequest): ComplexResponse {
        accessService.current().requireOnPlatform(Permissions.COMPLEX_SETTINGS_UPDATE)
        return ComplexResponse.from(complexService.updateSettings(id, request.name, request.address))
    }

    // Refused with 409 while any society belongs to the complex.
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.COMPLEX_SETTINGS_DELETE)
    fun delete(@PathVariable id: Long) {
        accessService.current().requireOnPlatform(Permissions.COMPLEX_SETTINGS_DELETE)
        complexService.delete(id)
    }
}
