package org.devvikram.societymanagement.onboarding.dto

import org.devvikram.societymanagement.onboarding.service.Checklist
import org.devvikram.societymanagement.society.model.SocietySource
import org.devvikram.societymanagement.society.model.SocietyStatus
import java.time.Instant

// Profile is required; every other step is optional and only reported.
data class OnboardingResponse(
    val societyId: Long,
    val name: String,
    val status: SocietyStatus,
    val source: SocietySource,
    val createdAt: Instant?,
    val wentLiveAt: Instant?,
    val profile: ProfileStep,
    val structure: StructureStep,
    val residents: PeopleStep,
    val committee: PeopleStep,
    val settings: SettingsStep,
    val stepsDone: Int,
    val stepsTotal: Int,
    val canGoLive: Boolean,
) {
    data class ProfileStep(val done: Boolean, val missing: List<String>)
    data class StructureStep(val done: Boolean, val wings: Int, val flats: Int)
    data class PeopleStep(val done: Boolean, val members: Long, val openInvitations: Int)
    data class SettingsStep(val done: Boolean, val customized: Int)

    companion object {
        fun from(c: Checklist) = OnboardingResponse(
            societyId = c.society.id!!,
            name = c.society.name,
            status = c.society.status,
            source = c.society.source,
            createdAt = c.society.createdAt,
            wentLiveAt = c.society.wentLiveAt,
            profile = ProfileStep(c.profileDone, c.missingProfile),
            structure = StructureStep(c.structureDone, c.wings, c.flats),
            residents = PeopleStep(c.residentsDone, c.residents, c.residentInvitations),
            committee = PeopleStep(c.committeeDone, c.members, c.memberInvitations),
            settings = SettingsStep(c.settingsDone, c.customizedSettings),
            stepsDone = c.stepsDone,
            stepsTotal = 5,
            canGoLive = c.canGoLive,
        )
    }
}
