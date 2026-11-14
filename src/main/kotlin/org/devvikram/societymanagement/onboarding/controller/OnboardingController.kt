package org.devvikram.societymanagement.onboarding.controller

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.onboarding.dto.OnboardingResponse
import org.devvikram.societymanagement.onboarding.service.OnboardingService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

// The setup checklist and going live. Whoever may edit the society's profile (SECRETARY and above, or
// the platform) runs its setup.
@RestController
class OnboardingController(
    private val onboardingService: OnboardingService,
    private val accessService: AccessService,
) {
    @GetMapping("/api/societies/{societyId}/onboarding")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_VIEW)
    fun checklist(@PathVariable societyId: Long): OnboardingResponse {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_VIEW, societyId)
        return OnboardingResponse.from(onboardingService.checklist(societyId))
    }

    @PostMapping("/api/societies/{societyId}/go-live")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun goLive(@PathVariable societyId: Long): OnboardingResponse {
        val access = accessService.current()
        access.requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, societyId)
        return OnboardingResponse.from(onboardingService.goLive(societyId, access.userId))
    }
}
