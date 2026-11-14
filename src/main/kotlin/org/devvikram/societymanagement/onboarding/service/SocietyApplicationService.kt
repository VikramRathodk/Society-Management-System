package org.devvikram.societymanagement.onboarding.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.common.exception.TooManyRequestsException
import org.devvikram.societymanagement.onboarding.model.ApplicationStatus
import org.devvikram.societymanagement.onboarding.model.SocietyApplication
import org.devvikram.societymanagement.onboarding.repository.SocietyApplicationRepository
import org.devvikram.societymanagement.society.model.SocietySource
import org.devvikram.societymanagement.society.model.SocietyStatus
import org.devvikram.societymanagement.society.service.SocietyProfileCommand
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.UserService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

// What the public form sends. Signed-in applicants use their account, so applicant name, phone and password
// are only needed from someone new.
data class SubmitApplicationCommand(
    val societyName: String,
    val city: String,
    val address: String?,
    val approxFlats: Int?,
    val applicantName: String?,
    val applicantPhone: String?,
    val applicantEmail: String?,
    val password: String?,
    val message: String?,
)

// Self-signup: an application waits for the super admin, who calls the applicant to confirm (there is no
// OTP). Approving creates the society in Setup, the applicant's account and their SECRETARY role in one
// transaction, so a failure leaves nothing half made.
@Service
class SocietyApplicationService(
    private val repository: SocietyApplicationRepository,
    private val societyService: SocietyService,
    private val userService: UserService,
    private val clock: Clock,
    @Value("\${app.onboarding.self-signup-enabled:true}") val selfSignupEnabled: Boolean,
    @Value("\${app.onboarding.applications-per-ip-per-hour:5}") private val perIpPerHour: Long,
) {
    @Transactional
    fun submit(command: SubmitApplicationCommand, signedInUserId: Long?, ip: String?): SocietyApplication {
        if (!selfSignupEnabled) throw ConflictException("Society registration is closed. Contact us to get your society on board.")
        val now = clock.instant()
        if (ip != null && repository.countFromIpSince(ip, now.minus(Duration.ofHours(1))) >= perIpPerHour) {
            throw TooManyRequestsException("Too many registrations from this network. Try again in an hour.")
        }

        val existing = signedInUserId?.let { userService.getById(it) }
        val name: String
        val phone: String
        val passwordHash: String?
        if (existing != null) {
            name = existing.name
            phone = existing.phone
            passwordHash = null
        } else {
            name = requireNotNull(command.applicantName?.trim()?.takeIf { it.isNotEmpty() }) { "Your name is required" }
            phone = requireNotNull(command.applicantPhone?.trim()?.takeIf { it.isNotEmpty() }) { "Your phone number is required" }
            val password = requireNotNull(command.password?.takeIf { it.isNotEmpty() }) { "Choose a password for your account" }
            if (userService.getByPhone(phone) != null) {
                throw ConflictException("This phone number already has an account. Sign in and register the society from there.")
            }
            passwordHash = userService.hashPassword(password)
        }
        if (repository.hasPending(phone)) {
            throw ConflictException("There is already a registration waiting for this phone number. We'll call you soon.")
        }
        return repository.save(
            SocietyApplication(
                societyName = command.societyName.trim(),
                city = command.city.trim(),
                address = command.address.clean(),
                approxFlats = command.approxFlats,
                applicantName = name,
                applicantPhone = phone,
                applicantEmail = command.applicantEmail.clean() ?: existing?.email,
                passwordHash = passwordHash,
                applicantUser = existing,
                message = command.message.clean(),
                createdAt = now,
                createdIp = ip,
            )
        )
    }

    // The applicant checks on their registration with its number and their phone.
    @Transactional(readOnly = true)
    fun status(id: Long, phone: String): SocietyApplication =
        repository.findByIdAndPhone(id, phone.trim())
            ?: throw NoSuchElementException("No registration with that number for this phone number")

    @Transactional(readOnly = true)
    fun list(status: ApplicationStatus?): List<SocietyApplication> = repository.list(status)

    @Transactional(readOnly = true)
    fun get(id: Long): SocietyApplication = find(id)

    @Transactional
    fun approve(id: Long, reviewerId: Long): SocietyApplication {
        val application = requirePending(find(id))
        val now = clock.instant()
        // Every refusal comes before anything is created.
        application.applicantUser?.let { if (!it.isActive) throw ConflictException("${it.name}'s account is deactivated") }
            ?: run {
                if (userService.getByPhone(application.applicantPhone) != null) {
                    throw ConflictException(
                        "${application.applicantPhone} has signed up since applying. Reject this registration and ask them to apply again while signed in."
                    )
                }
            }

        val society = societyService.createSociety(
            name = application.societyName,
            address = application.address,
            status = SocietyStatus.SETUP,
            source = SocietySource.SELF_SIGNUP,
        )
        // What the form already told us goes into the profile; the secretary completes the rest.
        societyService.updateProfile(
            society.id!!,
            SocietyProfileCommand(city = application.city, contactPhone = application.applicantPhone, contactEmail = application.applicantEmail),
        )
        val secretary = AssignRoleCommand(roleCode = "SECRETARY", societyId = society.id)
        val user = application.applicantUser?.also { userService.assignRole(it.id!!, secretary) }
            ?: userService.createUserWithPasswordHash(
                application.applicantName, application.applicantPhone, application.applicantEmail,
                requireNotNull(application.passwordHash), secretary,
            )

        application.status = ApplicationStatus.APPROVED
        application.society = society
        application.applicantUser = user
        application.passwordHash = null
        decided(application, reviewerId, now)
        return repository.save(application)
    }

    @Transactional
    fun reject(id: Long, reason: String, reviewerId: Long): SocietyApplication {
        val application = requirePending(find(id))
        application.status = ApplicationStatus.REJECTED
        application.reason = requireNotNull(reason.clean()) { "Give a reason the applicant will see" }
        application.passwordHash = null
        decided(application, reviewerId, clock.instant())
        return repository.save(application)
    }

    private fun decided(application: SocietyApplication, reviewerId: Long, now: Instant) {
        application.reviewedAt = now
        application.reviewedBy = userService.getById(reviewerId)
    }

    private fun requirePending(application: SocietyApplication): SocietyApplication {
        if (application.status != ApplicationStatus.PENDING) {
            throw ConflictException("This registration was already ${application.status.name.lowercase()}")
        }
        return application
    }

    private fun find(id: Long): SocietyApplication =
        repository.findById(id) ?: throw NoSuchElementException("Registration $id not found")

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
