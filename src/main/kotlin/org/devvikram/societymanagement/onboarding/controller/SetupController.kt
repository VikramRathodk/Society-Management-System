package org.devvikram.societymanagement.onboarding.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.onboarding.dto.PlannedWingResponse
import org.devvikram.societymanagement.onboarding.dto.ResidentImportResponse
import org.devvikram.societymanagement.onboarding.dto.SendResultResponse
import org.devvikram.societymanagement.onboarding.dto.StructureRequest
import org.devvikram.societymanagement.onboarding.dto.UploadedSheetResponse
import org.devvikram.societymanagement.onboarding.service.ResidentImportService
import org.devvikram.societymanagement.onboarding.service.SheetColumns
import org.devvikram.societymanagement.onboarding.service.StructureService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

// Setup wizard steps 2 and 3, usable later while live too. Wings & flats from a pattern need the right to
// add flats; the residents sheet is run by whoever runs the setup (SECRETARY and above, or the platform).
@RestController
class SetupController(
    private val structureService: StructureService,
    private val residentImportService: ResidentImportService,
    private val accessService: AccessService,
    @Value("\${app.web-base-url}") private val webBaseUrl: String,
) {
    // ---- Wings & flats ----

    @PostMapping("/api/societies/{societyId}/structure/preview")
    @RequiresPermission(Permissions.FLAT_STRUCTURE_CREATE)
    fun previewStructure(@PathVariable societyId: Long, @Valid @RequestBody request: StructureRequest): List<PlannedWingResponse> {
        accessService.current().requireIn(Permissions.FLAT_STRUCTURE_CREATE, societyId)
        return structureService.preview(societyId, request.toPatterns()).map(PlannedWingResponse::from)
    }

    @PostMapping("/api/societies/{societyId}/structure/generate")
    @RequiresPermission(Permissions.FLAT_STRUCTURE_CREATE)
    fun generateStructure(@PathVariable societyId: Long, @Valid @RequestBody request: StructureRequest): List<PlannedWingResponse> {
        accessService.current().requireIn(Permissions.FLAT_STRUCTURE_CREATE, societyId)
        return structureService.generate(societyId, request.toPatterns()).map(PlannedWingResponse::from)
    }

    // ---- Residents from a sheet ----

    // CSV with a header row. Columns are guessed from the header unless flatColumn, nameColumn and
    // phoneColumn (and optionally wingColumn, typeColumn; 1-based) are given. Replaces any unsent sheet.
    @PostMapping("/api/societies/{societyId}/resident-imports")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun upload(
        @PathVariable societyId: Long,
        @RequestPart("file") file: MultipartFile,
        @RequestParam(required = false) flatColumn: Int?,
        @RequestParam(required = false) nameColumn: Int?,
        @RequestParam(required = false) phoneColumn: Int?,
        @RequestParam(required = false) wingColumn: Int?,
        @RequestParam(required = false) typeColumn: Int?,
    ): UploadedSheetResponse {
        val access = accessService.current()
        access.requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, societyId)
        val chosen = listOfNotNull(flatColumn, nameColumn, phoneColumn, wingColumn, typeColumn).isNotEmpty()
        val columns = if (chosen) {
            require(flatColumn != null && nameColumn != null && phoneColumn != null) {
                "Choose the flat, name and phone columns"
            }
            SheetColumns(flatColumn, nameColumn, phoneColumn, wingColumn, typeColumn)
        } else {
            null
        }
        return UploadedSheetResponse.from(
            residentImportService.upload(societyId, file.originalFilename, file.bytes, columns, access.userId)
        )
    }

    // The unsent sheet, checked again; 204 when there is none.
    @GetMapping("/api/societies/{societyId}/resident-imports/current")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_VIEW)
    fun current(@PathVariable societyId: Long): ResponseEntity<ResidentImportResponse> {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_VIEW, societyId)
        val current = residentImportService.current(societyId) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(ResidentImportResponse.from(current))
    }

    @DeleteMapping("/api/societies/{societyId}/resident-imports/current/rows/{rowId}")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun removeRow(@PathVariable societyId: Long, @PathVariable rowId: Long): ResidentImportResponse {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, societyId)
        return ResidentImportResponse.from(residentImportService.removeRow(societyId, rowId))
    }

    @DeleteMapping("/api/societies/{societyId}/resident-imports/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun discard(@PathVariable societyId: Long) {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, societyId)
        residentImportService.discard(societyId)
    }

    // Live societies only (409 in Setup: the sheet goes out at go-live). The links are only in this response.
    @PostMapping("/api/societies/{societyId}/resident-imports/current/send")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun send(@PathVariable societyId: Long): SendResultResponse {
        val access = accessService.current()
        access.requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, societyId)
        return SendResultResponse.from(residentImportService.send(societyId, access), webBaseUrl)
    }
}
