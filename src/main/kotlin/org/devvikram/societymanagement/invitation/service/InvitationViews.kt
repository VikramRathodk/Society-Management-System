package org.devvikram.societymanagement.invitation.service

import org.devvikram.societymanagement.invitation.model.Invitation
import org.devvikram.societymanagement.invitation.model.InvitationStatus
import org.devvikram.societymanagement.user.model.ResidentType
import java.time.Instant

data class CreateInvitationCommand(
    val societyId: Long,
    val phone: String,
    val name: String,
    val roleCode: String,
    val flatId: Long? = null,
    val residentType: ResidentType? = null,
)

// Flat values, built inside the transaction (open-in-view is off).
data class InvitationView(
    val id: Long,
    val societyId: Long,
    val societyName: String,
    val roleCode: String,
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
) {
    companion object {
        fun of(invitation: Invitation, now: Instant) = InvitationView(
            id = invitation.id!!,
            societyId = invitation.society.id!!,
            societyName = invitation.society.name,
            roleCode = invitation.role.code,
            roleName = invitation.role.name,
            flatId = invitation.flat?.id,
            flatLabel = flatLabel(invitation),
            residentType = invitation.residentType,
            phone = invitation.phone,
            name = invitation.name,
            status = invitation.statusAt(now),
            invitedById = invitation.invitedBy.id!!,
            invitedByName = invitation.invitedBy.name,
            createdAt = invitation.createdAt,
            expiresAt = invitation.expiresAt,
            closedAt = invitation.closedAt,
            acceptedUserId = invitation.acceptedUser?.id,
        )
    }
}

// What the person holding the link sees before accepting. No ids, and the phone only masked.
data class InvitationPreview(
    val societyName: String,
    val roleCode: String,
    val roleName: String,
    val flatLabel: String?,
    val residentType: ResidentType?,
    val name: String,
    val maskedPhone: String,
    val invitedByName: String,
    val status: InvitationStatus,
    val expiresAt: Instant,
    // The phone already has an account: accept after logging in instead of signing up.
    val hasAccount: Boolean,
)

// A new link; the token exists only here and in the link itself.
data class IssuedInvitation(val invitation: InvitationView, val token: String)

internal fun flatLabel(invitation: Invitation): String? =
    invitation.flat?.let { "${it.wing.name}-${it.flatNumber}" }
