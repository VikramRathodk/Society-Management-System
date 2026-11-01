package org.devvikram.societymanagement.maintenance.model

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
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.user.model.User
import java.time.Instant
import java.time.LocalDate

// A cycle is created already issued (the preview is never stored). Cancelling it cancels its bills and
// frees the period for a new cycle.
enum class CycleStatus { ISSUED, CANCELLED }

// OPENING_BALANCE: the one-off cycle holding dues from before the society used the app. It is ignored when
// suggesting or checking regular periods.
enum class CycleKind { REGULAR, OPENING_BALANCE }

// One billing period of a society: every flat got a bill for it.
@Entity
@Table(name = "maintenance_cycle")
class MaintenanceCycle(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @Column(name = "period_start", nullable = false)
    var periodStart: LocalDate,

    @Column(name = "period_end", nullable = false)
    var periodEnd: LocalDate,

    @Column(name = "due_date", nullable = false)
    var dueDate: LocalDate,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issued_by", nullable = false)
    var issuedBy: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: CycleKind = CycleKind.REGULAR,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: CycleStatus = CycleStatus.ISSUED

    @Column(name = "issued_at", nullable = false)
    var issuedAt: Instant? = null

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    var cancelledBy: User? = null

    @Column(name = "cancel_reason")
    var cancelReason: String? = null

    @PrePersist
    fun onCreate() {
        if (issuedAt == null) issuedAt = Instant.now()
    }
}
