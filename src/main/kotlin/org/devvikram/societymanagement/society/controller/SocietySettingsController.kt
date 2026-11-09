package org.devvikram.societymanagement.society.controller

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.society.dto.SocietySettingsResponse
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

// A society's configurable rules (SECRETARY and above). PATCH takes only the settings that change,
// e.g. {"tenantsSeeMaintenance": true}; null puts a setting back to its default.
@RestController
class SocietySettingsController(
    private val settingsService: SocietySettingsService,
    private val accessService: AccessService,
) {

    @GetMapping("/api/societies/{societyId}/settings")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_VIEW)
    fun settings(@PathVariable societyId: Long): SocietySettingsResponse {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_VIEW, societyId)
        return SocietySettingsResponse.from(societyId, settingsService.settingsOf(societyId))
    }

    @PatchMapping("/api/societies/{societyId}/settings")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun update(@PathVariable societyId: Long, @RequestBody changes: Map<String, Any?>): SocietySettingsResponse {
        val access = accessService.current()
        if (!access.canSocietyWide(Permissions.SOCIETY_SETTINGS_UPDATE, societyId)) {
            throw AccessDeniedException("Missing permission: ${Permissions.SOCIETY_SETTINGS_UPDATE} for this society")
        }
        settingsService.update(societyId, changes, access.userId)
        return SocietySettingsResponse.from(societyId, settingsService.settingsOf(societyId))
    }
}
