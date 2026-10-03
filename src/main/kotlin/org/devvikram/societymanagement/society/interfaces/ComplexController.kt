package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.Valid
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
import java.util.UUID

@RestController
@RequestMapping("/api/complexes")
class ComplexController(private val complexService: ComplexService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateComplexRequest): ComplexResponse =
        ComplexResponse.from(complexService.createComplex(request.name, request.address))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): ComplexResponse = ComplexResponse.from(complexService.getById(id))

    @GetMapping
    fun getAll(): List<ComplexResponse> = complexService.getAll().map(ComplexResponse::from)

    @PatchMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateComplexRequest): ComplexResponse =
        ComplexResponse.from(complexService.updateSettings(id, request.name, request.address))
}
