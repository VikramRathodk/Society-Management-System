package org.devvikram.societymanagement.society.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// Schema now, workflow deferred to Phase 2.
@Entity
@Table(name = "staff_member")
class StaffMember(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    // nullable: society-wide staff (e.g. sweeper) vs flat-specific (maid)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flat_id")
    var flat: Flat? = null,

    @Column(nullable = false)
    var name: String,

    var phone: String? = null,

    @Column(name = "photo_url")
    var photoUrl: String? = null,

    // maid, cook, driver, cleaner, etc.
    @Column(name = "staff_type")
    var staffType: String? = null,

    @Column(name = "id_proof_url")
    var idProofUrl: String? = null,

    @Column(name = "is_active")
    var isActive: Boolean = true,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at")
    var createdAt: Instant? = null

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}
