package org.devvikram.societymanagement.invitation.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.invitation.model.InvitationStatus
import org.devvikram.societymanagement.invitation.service.CreateInvitationCommand
import org.devvikram.societymanagement.invitation.service.FlatInviteOption
import org.devvikram.societymanagement.invitation.service.InvitationPreview
import org.devvikram.societymanagement.invitation.service.InvitationView
import org.devvikram.societymanagement.invitation.service.IssuedInvitation
import org.devvikram.societymanagement.user.model.ResidentType
import java.time.Instant

// role is a role code; flatId and residentType are required for roles tied to a flat (e.g. RESIDENT).
data class CreateInvitationRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^\\s*\\+?[0-9]{10,14}\\s*$", message = "must be a phone number of 10 to 14 digits")
    val phone: String,
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:NotBlank val role: String,
    val flatId: Long? = null,
    val residentType: ResidentType? = null,
) {
    fun toCommand(societyId: Long) = CreateInvitationCommand(societyId, phone, name, role, flatId, residentType)
}

// New users send a password (and may correct their name); a logged-in user sends nothing.
data class AcceptInvitationRequest(
    @field:Size(max = 255) val name: String? = null,
    @field:Size(min = 8, max = 72) val password: String? = null,
)

data class InvitationResponse(
    val id: Long,
    val societyId: Long,
    val societyName: String,
    val role: String,
    val roleName: String,
    val flatId: Long?,
    val flatLabel: String?,
    val residentType: ResidentType?,
    val phone: String,
    val name: String,
    val status: InvitationStatus,
    val invitedById: Long,
    val invitedByName: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val closedAt: Instant?,
    val acceptedUserId: Long?,
    val approvedAt: Instant?,
    val approvedByName: String?,
) {
    companion object {
        fun from(v: InvitationView) = InvitationResponse(
            v.id, v.societyId, v.societyName, v.roleCode, v.roleName, v.flatId, v.flatLabel, v.residentType,
            v.phone, v.name, v.status, v.invitedById, v.invitedByName, v.createdAt, v.expiresAt, v.closedAt,
            v.acceptedUserId, v.approvedAt, v.approvedByName,
        )
    }
}

// Only returned when a link is made (create, resend): the token is never stored, so this is the one
// chance to share the link or show its QR code.
data class IssuedInvitationResponse(
    val invitation: InvitationResponse,
    val token: String,
    val inviteUrl: String,
) {
    companion object {
        fun from(issued: IssuedInvitation, webBaseUrl: String) = IssuedInvitationResponse(
            invitation = InvitationResponse.from(issued.invitation),
            token = issued.token,
            inviteUrl = "${webBaseUrl.trimEnd('/')}/invite/${issued.token}",
        )
    }
}

data class InvitationPreviewResponse(
    val societyName: String,
    val role: String,
    val roleName: String,
    val flatLabel: String?,
    val residentType: ResidentType?,
    val name: String,
    val maskedPhone: String,
    val invitedByName: String,
    val status: InvitationStatus,
    val expiresAt: Instant,
    val hasAccount: Boolean,
) {
    companion object {
        fun from(p: InvitationPreview) = InvitationPreviewResponse(
            p.societyName, p.roleCode, p.roleName, p.flatLabel, p.residentType, p.name, p.maskedPhone,
            p.invitedByName, p.status, p.expiresAt, p.hasAccount,
        )
    }
}

// A flat the caller may invite residents to as a resident, and as which resident types.
data class FlatInviteOptionResponse(
    val flatId: Long,
    val flatLabel: String,
    val role: String,
    val roleName: String,
    val residentTypes: List<ResidentType>,
    val needsApproval: Boolean,
) {
    companion object {
        fun from(o: FlatInviteOption) =
            FlatInviteOptionResponse(o.flatId, o.flatLabel, o.roleCode, o.roleName, o.residentTypes, o.needsApproval)
    }
}
