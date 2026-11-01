package org.devvikram.societymanagement.maintenance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.maintenance.dto.AddChargeHeadRequest
import org.devvikram.societymanagement.maintenance.dto.ChargeHeadResponse
import org.devvikram.societymanagement.maintenance.dto.SettingsResponse
import org.devvikram.societymanagement.maintenance.dto.UpdateChargeHeadRequest
import org.devvikram.societymanagement.maintenance.dto.UpdateSettingsRequest
import org.devvikram.societymanagement.maintenance.service.AddChargeHeadCommand
import org.devvikram.societymanagement.maintenance.service.MaintenanceConfigService
import org.devvikram.societymanagement.maintenance.service.UpdateChargeHeadCommand
import org.devvikram.societymanagement.maintenance.service.UpdateSettingsCommand
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Everyone with a role in the society may read what it charges (residents see what their bill is made
// of); changing it is a society-wide act (SECRETARY and above), never a flat-tied role's.
@RestController
class MaintenanceConfigController(
    private val configService: MaintenanceConfigService,
    private val accessService: AccessService,
) {

    @GetMapping("/api/societies/{societyId}/maintenance/settings")
    @RequiresPermission(Permissions.MAINTENANCE_CHARGE_VIEW)
    fun settings(@PathVariable societyId: Long): SettingsResponse {
        accessService.current().requireIn(Permissions.MAINTENANCE_CHARGE_VIEW, societyId)
        return SettingsResponse.from(configService.settingsOf(societyId))
    }

    @PutMapping("/api/societies/{societyId}/maintenance/settings")
    @RequiresPermission(Permissions.MAINTENANCE_CHARGE_UPDATE)
    fun updateSettings(@PathVariable societyId: Long, @Valid @RequestBody request: UpdateSettingsRequest): SettingsResponse {
        val access = requireSocietyWide(societyId)
        configService.updateSettings(
            societyId,
            UpdateSettingsCommand(request.billingFrequency!!, request.dueDays!!, request.roundToRupee!!),
            access.userId,
        )
        return SettingsResponse.from(configService.settingsOf(societyId))
    }

    @GetMapping("/api/societies/{societyId}/maintenance/charge-heads")
    @RequiresPermission(Permissions.MAINTENANCE_CHARGE_VIEW)
    fun chargeHeads(@PathVariable societyId: Long): List<ChargeHeadResponse> {
        accessService.current().requireIn(Permissions.MAINTENANCE_CHARGE_VIEW, societyId)
        return configService.chargeHeads(societyId).map(ChargeHeadResponse::from)
    }

    @PostMapping("/api/societies/{societyId}/maintenance/charge-heads")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.MAINTENANCE_CHARGE_UPDATE)
    fun addChargeHead(@PathVariable societyId: Long, @Valid @RequestBody request: AddChargeHeadRequest): ChargeHeadResponse {
        requireSocietyWide(societyId)
        val id = configService.addChargeHead(
            societyId,
            AddChargeHeadCommand(request.name, request.calcType!!, request.amount!!, request.active, request.sortOrder),
        )
        return ChargeHeadResponse.from(configService.getChargeHead(id))
    }

    @PatchMapping("/api/maintenance/charge-heads/{id}")
    @RequiresPermission(Permissions.MAINTENANCE_CHARGE_UPDATE)
    fun updateChargeHead(@PathVariable id: Long, @Valid @RequestBody request: UpdateChargeHeadRequest): ChargeHeadResponse {
        requireSocietyWide(configService.getChargeHead(id).society.id!!)
        configService.updateChargeHead(
            id,
            UpdateChargeHeadCommand(request.name, request.calcType, request.amount, request.active, request.sortOrder),
        )
        return ChargeHeadResponse.from(configService.getChargeHead(id))
    }

    @DeleteMapping("/api/maintenance/charge-heads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.MAINTENANCE_CHARGE_UPDATE)
    fun deleteChargeHead(@PathVariable id: Long) {
        requireSocietyWide(configService.getChargeHead(id).society.id!!)
        configService.deleteChargeHead(id)
    }

    private fun requireSocietyWide(societyId: Long) = accessService.current().also {
        if (!it.canSocietyWide(Permissions.MAINTENANCE_CHARGE_UPDATE, societyId)) {
            throw AccessDeniedException("Missing permission: ${Permissions.MAINTENANCE_CHARGE_UPDATE} for this society")
        }
    }
}
