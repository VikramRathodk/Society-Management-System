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
import org.devvikram.societymanagement.user.model.User
import java.time.Instant

// One step in a complaint's timeline: raised (fromStatus null) or a status change, with an optional note.
@Entity
@Table(name = "complaint_activity")
class ComplaintActivity(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false)
    var complaint: Complaint,

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    var fromStatus: ComplaintStatus?,

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    var toStatus: ComplaintStatus,

    var note: String? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    var actor: User,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}
