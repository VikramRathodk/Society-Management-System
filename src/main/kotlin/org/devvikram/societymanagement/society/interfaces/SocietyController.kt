package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.Valid
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

@RestController
@RequestMapping("/api/societies")
class SocietyController(private val societyService: SocietyService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateSocietyRequest): SocietyResponse =
        SocietyResponse.from(societyService.createSociety(request.name, request.address, request.complexId))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): SocietyResponse = SocietyResponse.from(societyService.getById(id))

    @GetMapping
    fun getAll(): List<SocietyResponse> = societyService.getAll().map(SocietyResponse::from)

    @PatchMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateSocietyRequest): SocietyResponse =
        SocietyResponse.from(societyService.updateSettings(id, request.name, request.address))
}
