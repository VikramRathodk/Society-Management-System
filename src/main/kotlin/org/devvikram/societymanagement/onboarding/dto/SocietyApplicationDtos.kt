package org.devvikram.societymanagement.onboarding.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.invitation.dto.IssuedInvitationResponse
import org.devvikram.societymanagement.onboarding.model.ApplicationStatus
import org.devvikram.societymanagement.onboarding.model.SocietyApplication
import org.devvikram.societymanagement.onboarding.service.NewSociety
import org.devvikram.societymanagement.onboarding.service.NewSocietyCommand
import org.devvikram.societymanagement.onboarding.service.SubmitApplicationCommand
import org.devvikram.societymanagement.society.dto.SocietyResponse
import org.devvikram.societymanagement.society.model.SocietyStatus
import java.time.Instant

private const val PHONE = "^\\s*\\+?[0-9]{10,14}\\s*$"
private const val PHONE_MESSAGE = "must be a phone number of 10 to 14 digits"

// ---- Platform-led ----

data class SecretaryRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:NotBlank @field:Pattern(regexp = PHONE, message = PHONE_MESSAGE) val phone: String,
)

data class NewSocietyRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:Size(max = 500) val address: String? = null,
    val complexId: Long? = null,
    val startLive: Boolean = false,
    @field:NotNull @field:Valid val secretary: SecretaryRequest? = null,
) {
    fun toCommand() = NewSocietyCommand(
        name = name.trim(),
        address = address?.trim()?.ifEmpty { null },
        complexId = complexId,
        status = if (startLive) SocietyStatus.LIVE else SocietyStatus.SETUP,
        secretaryName = secretary!!.name.trim(),
        secretaryPhone = secretary.phone.trim(),
    )
}

data class NewSocietyResponse(val society: SocietyResponse, val secretaryInvitation: IssuedInvitationResponse) {
    companion object {
        fun from(created: NewSociety, webBaseUrl: String) =
            NewSocietyResponse(SocietyResponse.from(created.society), IssuedInvitationResponse.from(created.invitation, webBaseUrl))
    }
}

// ---- Self-signup ----

// Applicant name, phone and password are needed unless the caller is signed in (their account is used).
data class SocietyApplicationRequest(
    @field:NotBlank @field:Size(max = 255) val societyName: String,
    @field:NotBlank @field:Size(max = 100) val city: String,
    @field:Size(max = 500) val address: String? = null,
    @field:Min(1) @field:Max(10000) val approxFlats: Int? = null,
    @field:Size(max = 255) val applicantName: String? = null,
    @field:Pattern(regexp = PHONE, message = PHONE_MESSAGE) val applicantPhone: String? = null,
    @field:Email @field:Size(max = 255) val applicantEmail: String? = null,
    @field:Size(min = 8, max = 72) val password: String? = null,
    @field:Size(max = 1000) val message: String? = null,
) {
    fun toCommand() = SubmitApplicationCommand(
        societyName, city, address, approxFlats, applicantName, applicantPhone, applicantEmail, password, message,
    )
}

data class RejectApplicationRequest(@field:NotBlank @field:Size(max = 500) val reason: String)

data class SelfSignupResponse(val enabled: Boolean)

// What the applicant sees about their own registration.
data class ApplicationStatusResponse(
    val number: Long,
    val societyName: String,
    val status: ApplicationStatus,
    val createdAt: Instant,
    val reviewedAt: Instant?,
    val reason: String?,
) {
    companion object {
        fun from(a: SocietyApplication) =
            ApplicationStatusResponse(a.id!!, a.societyName, a.status, a.createdAt, a.reviewedAt, a.reason)
    }
}

// The super admin's view.
data class SocietyApplicationResponse(
    val id: Long,
    val societyName: String,
    val city: String,
    val address: String?,
    val approxFlats: Int?,
    val applicantName: String,
    val applicantPhone: String,
    val applicantEmail: String?,
    // Applied while signed in, so approving adds the role to that account instead of creating one.
    val existingAccount: Boolean,
    val message: String?,
    val status: ApplicationStatus,
    val createdAt: Instant,
    val reviewedAt: Instant?,
    val reviewedByName: String?,
    val reason: String?,
    val societyId: Long?,
) {
    companion object {
        fun from(a: SocietyApplication) = SocietyApplicationResponse(
            id = a.id!!,
            societyName = a.societyName,
            city = a.city,
            address = a.address,
            approxFlats = a.approxFlats,
            applicantName = a.applicantName,
            applicantPhone = a.applicantPhone,
            applicantEmail = a.applicantEmail,
            existingAccount = a.status == ApplicationStatus.PENDING && a.applicantUser != null,
            message = a.message,
            status = a.status,
            createdAt = a.createdAt,
            reviewedAt = a.reviewedAt,
            reviewedByName = a.reviewedBy?.name,
            reason = a.reason,
            societyId = a.society?.id,
        )
    }
}
