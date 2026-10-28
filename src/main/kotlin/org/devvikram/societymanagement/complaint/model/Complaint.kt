package org.devvikram.societymanagement.complaint.model

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
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.user.model.User
import java.time.Instant

enum class ComplaintCategory { PLUMBING, ELECTRICAL, SECURITY, OTHER }

// OPEN → IN_PROGRESS → RESOLVED → CLOSED, managed by the committee. OPEN may go straight to RESOLVED,
// and a RESOLVED complaint may be reopened (back to OPEN). CLOSED is final.
enum class ComplaintStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED }

@Entity
@Table(name = "complaint")
class Complaint(
    // Always the flat's own society — derived from the flat when the complaint is raised.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flat_id", nullable = false)
    var flat: Flat,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var category: ComplaintCategory,

    @Column(nullable = false)
    var description: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raised_by", nullable = false)
    var raisedBy: User,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ComplaintStatus = ComplaintStatus.OPEN

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    // Last edit or status change, and who made it.
    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    var updatedBy: User? = null

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}
