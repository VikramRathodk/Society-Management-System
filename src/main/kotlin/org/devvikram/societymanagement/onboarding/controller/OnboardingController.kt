package org.devvikram.societymanagement.onboarding.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.onboarding.dto.NewSocietyRequest
import org.devvikram.societymanagement.onboarding.dto.NewSocietyResponse
import org.devvikram.societymanagement.onboarding.dto.OnboardingResponse
import org.devvikram.societymanagement.onboarding.service.OnboardingService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// The setup checklist and going live. Whoever may edit the society's profile (SECRETARY and above, or
// the platform) runs its setup.
@RestController
class OnboardingController(
    private val onboardingService: OnboardingService,
    private val accessService: AccessService,
    @Value("\${app.web-base-url}") private val webBaseUrl: String,
) {
    // Platform-led: a new society (in Setup unless startLive) with an invitation for its secretary. The
    // invitation link is in the response, as when inviting anyone.
    @PostMapping("/api/onboarding/societies")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_CREATE)
    fun createWithSecretary(@Valid @RequestBody request: NewSocietyRequest): NewSocietyResponse {
        val access = accessService.current()
        access.requireOnPlatform(Permissions.SOCIETY_SETTINGS_CREATE)
        return NewSocietyResponse.from(onboardingService.createWithSecretary(request.toCommand(), access), webBaseUrl)
    }

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
