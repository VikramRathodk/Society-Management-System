package org.devvikram.societymanagement.onboarding.service

import org.devvikram.societymanagement.invitation.repository.InvitationRepository
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.repository.SocietySettingRepository
import org.devvikram.societymanagement.society.repository.WingRepository
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

// Where a society is in its setup: the required profile and the optional steps, each with what it has
// so far. Optional steps never block going live.
data class Checklist(
    val society: Society,
    val missingProfile: List<String>,
    val wings: Int,
    val flats: Int,
    val residents: Long,
    val residentInvitations: Int,
    val members: Long,
    val memberInvitations: Int,
    val customizedSettings: Int,
) {
    val profileDone get() = missingProfile.isEmpty()
    val structureDone get() = flats > 0
    val residentsDone get() = residents > 0 || residentInvitations > 0
    // The secretary alone isn't a committee: someone else holds or has been invited to a society-wide role.
    val committeeDone get() = members + memberInvitations >= 2
    val settingsDone get() = customizedSettings > 0
    val stepsDone get() = listOf(profileDone, structureDone, residentsDone, committeeDone, settingsDone).count { it }
    val canGoLive get() = society.isSetup && profileDone
}

@Service
class OnboardingService(
    private val societyService: SocietyService,
    private val wingRepository: WingRepository,
    private val flatRepository: FlatRepository,
    private val settingRepository: SocietySettingRepository,
    private val userRoleRepository: UserRoleRepository,
    private val invitationRepository: InvitationRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun checklist(societyId: Long): Checklist {
        val society = societyService.getById(societyId)
        val open = invitationRepository.findOpenBySociety(societyId, clock.instant())
        return Checklist(
            society = society,
            missingProfile = society.missingProfile(),
            wings = wingRepository.findBySocietyId(societyId).size,
            flats = flatRepository.findBySocietyId(societyId).size,
            residents = userRoleRepository.countMembers(societyId, onFlats = true),
            residentInvitations = open.count { it.flat != null },
            members = userRoleRepository.countMembers(societyId, onFlats = false),
            memberInvitations = open.count { it.flat == null },
            customizedSettings = settingRepository.findBySociety(societyId).size,
        )
    }

    // Step O3 adds sending the staged resident invitations here.
    @Transactional
    fun goLive(societyId: Long, userId: Long): Checklist {
        societyService.goLive(societyId, userId, clock.instant())
        return checklist(societyId)
    }
}
