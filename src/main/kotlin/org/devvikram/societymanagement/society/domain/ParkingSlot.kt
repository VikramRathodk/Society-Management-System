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
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

// Schema now, workflow deferred to Phase 2.
@Entity
@Table(name = "parking_slot", uniqueConstraints = [UniqueConstraint(columnNames = ["society_id", "slot_number"])])
class ParkingSlot(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @Column(name = "slot_number", nullable = false)
    var slotNumber: String,

    // nullable: unassigned slot
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flat_id")
    var flat: Flat? = null,

    @Column(name = "vehicle_type")
    var vehicleType: String? = null,
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
