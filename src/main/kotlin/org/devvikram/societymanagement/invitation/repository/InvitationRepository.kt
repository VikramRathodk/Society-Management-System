package org.devvikram.societymanagement.invitation.repository

import org.devvikram.societymanagement.invitation.model.Invitation
import org.devvikram.societymanagement.invitation.model.InvitationStatus
import java.time.Instant

// status is the status as seen at `now` (a PENDING invitation past its expiry is EXPIRED); null = any.
data class InvitationSearch(
    val societyId: Long,
    val status: InvitationStatus? = null,
    // Only the invitations this user sent (a resident's own list); null = everyone's.
    val invitedById: Long? = null,
    val now: Instant,
    val page: Int = 0,
    val size: Int = 20,
)

interface InvitationRepository {
    fun save(invitation: Invitation): Invitation
    // Writes pending changes now, e.g. closing an old invitation before a new one takes its open slot.
    fun flush()
    fun findById(id: Long): Invitation?
    fun findByTokenHash(tokenHash: String): Invitation?
    // The open invitations for exactly this society, role, phone and flat (at most one, by the unique index).
    fun findOpen(societyId: Long, roleId: Long, phone: String, flatId: Long?): List<Invitation>
    fun findPendingForPhone(phone: String): List<Invitation>
    // Invitations to the flat that can still be accepted (unexpired PENDING) or are awaiting approval.
    fun countOpenForFlat(flatId: Long, now: Instant): Long
    // Newest first, with society, role, flat, wing and inviter loaded.
    fun search(search: InvitationSearch): List<Invitation>
}
