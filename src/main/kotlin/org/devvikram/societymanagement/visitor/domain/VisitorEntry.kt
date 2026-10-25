package org.devvikram.societymanagement.visitor.domain

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
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.user.domain.User
import java.time.Instant

// PENDING → APPROVED | REJECTED (a resident of the flat responds, or a society authority overrides;
// first response wins)
// APPROVED → ENTERED → EXITED (marked by gate security). PENDING never expires on its own.
enum class VisitorStatus { PENDING, APPROVED, REJECTED, ENTERED, EXITED }

@Entity
@Table(name = "visitor_entry")
class VisitorEntry(
    // Always the flat's own society — derived from the flat when the entry is logged.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flat_id", nullable = false)
    var flat: Flat,

    @Column(name = "visitor_name", nullable = false)
    var visitorName: String,

    @Column(name = "visitor_phone")
    var visitorPhone: String? = null,

    var purpose: String? = null,

    // Key into PhotoStorage, not a URL.
    @Column(name = "photo_key")
    var photoKey: String? = null,

    // The guard who logged the visitor.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "logged_by", nullable = false)
    var loggedBy: User,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: VisitorStatus = VisitorStatus.PENDING

    // The resident who approved or rejected — or, when respondedAsOverride, the society authority
    // who decided on the flat's behalf.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responded_by")
    var respondedBy: User? = null

    @Column(name = "responded_as_override", nullable = false)
    var respondedAsOverride: Boolean = false

    @Column(name = "logged_at", nullable = false)
    var loggedAt: Instant? = null

    @Column(name = "responded_at")
    var respondedAt: Instant? = null

    @Column(name = "entered_at")
    var enteredAt: Instant? = null

    @Column(name = "exited_at")
    var exitedAt: Instant? = null

    @PrePersist
    fun onCreate() {
        if (loggedAt == null) loggedAt = Instant.now()
    }
}
