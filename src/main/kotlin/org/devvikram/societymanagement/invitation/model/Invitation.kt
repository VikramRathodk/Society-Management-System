package org.devvikram.societymanagement.invitation.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.devvikram.societymanagement.authorization.model.Role
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.user.model.ResidentType
import org.devvikram.societymanagement.user.model.User
import java.time.Instant

// PENDING: the link works. AWAITING_APPROVAL / REJECTED: for invitations sent by residents (later).
// A PENDING invitation past its expiry counts as EXPIRED even before the row is updated.
enum class InvitationStatus {
    PENDING, AWAITING_APPROVAL, ACCEPTED, DECLINED, REJECTED, REVOKED, EXPIRED;

    val isOpen: Boolean get() = this == PENDING || this == AWAITING_APPROVAL
}

// An offer to one phone number of a role in a society (and a flat, for flat-tied roles).
@Entity
@Table(name = "invitation")
class Invitation(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    var role: Role,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flat_id")
    var flat: Flat?,

    @Enumerated(EnumType.STRING)
    @Column(name = "resident_type")
    var residentType: ResidentType?,

    @Column(nullable = false)
    var phone: String,

    @Column(nullable = false)
    var name: String,

    // SHA-256 (hex) of the link's token; the token itself is only ever in the link.
    @Column(name = "token_hash", nullable = false, unique = true)
    var tokenHash: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by", nullable = false)
    var invitedBy: User,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: InvitationStatus = InvitationStatus.PENDING

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    var closedBy: User? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_user_id")
    var acceptedUser: User? = null

    fun statusAt(now: Instant): InvitationStatus =
        if (status == InvitationStatus.PENDING && !expiresAt.isAfter(now)) InvitationStatus.EXPIRED else status
}
