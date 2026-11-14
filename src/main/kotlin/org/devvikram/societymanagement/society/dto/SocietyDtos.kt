package org.devvikram.societymanagement.society.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.model.SocietySource
import org.devvikram.societymanagement.society.model.SocietyStatus
import org.devvikram.societymanagement.society.service.SocietyProfileCommand
import java.time.Instant

// A new society starts in SETUP for its committee to finish; `startLive` skips that (e.g. moving an
// existing society in with its residents already known).
data class CreateSocietyRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:Size(max = 500) val address: String? = null,
    val complexId: Long? = null,
    val startLive: Boolean = false,
)

// Any field left out stays as it is; an empty string clears it (except the name).
data class UpdateSocietyRequest(
    @field:Size(max = 255) val name: String? = null,
    @field:Size(max = 500) val address: String? = null,
    @field:Size(max = 50) val registrationNumber: String? = null,
    @field:Size(max = 100) val city: String? = null,
    @field:Size(max = 100) val state: String? = null,
    @field:Pattern(regexp = "^\\s*([1-9][0-9]{5})?\\s*$", message = "must be a 6-digit PIN code")
    val pincode: String? = null,
    @field:Pattern(regexp = "^\\s*(\\+?[0-9]{10,14})?\\s*$", message = "must be a phone number of 10 to 14 digits")
    val contactPhone: String? = null,
    @field:Email @field:Size(max = 255) val contactEmail: String? = null,
    @field:Size(max = 2000) val notes: String? = null,
) {
    fun toCommand() = SocietyProfileCommand(
        name, address, registrationNumber, city, state, pincode, contactPhone, contactEmail, notes,
    )
}

data class SocietyResponse(
    val id: Long,
    val name: String,
    val address: String?,
    val complexId: Long?,
    val status: SocietyStatus,
    val source: SocietySource,
    val createdAt: Instant?,
    val wentLiveAt: Instant?,
    val registrationNumber: String?,
    val city: String?,
    val state: String?,
    val pincode: String?,
    val contactPhone: String?,
    val contactEmail: String?,
    val hasLogo: Boolean,
    val notes: String?,
) {
    companion object {
        fun from(society: Society) = with(society) {
            SocietyResponse(
                id = id!!,
                name = name,
                address = address,
                complexId = complex?.id,
                status = status,
                source = source,
                createdAt = createdAt,
                wentLiveAt = wentLiveAt,
                registrationNumber = registrationNumber,
                city = city,
                state = state,
                pincode = pincode,
                contactPhone = contactPhone,
                contactEmail = contactEmail,
                hasLogo = logoKey != null,
                notes = notes,
            )
        }
    }
}
