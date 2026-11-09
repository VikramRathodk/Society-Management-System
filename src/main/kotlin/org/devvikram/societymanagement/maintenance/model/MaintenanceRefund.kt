package org.devvikram.societymanagement.maintenance.model

import jakarta.persistence.CascadeType
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
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// A waiting or completed refund holds the credit it takes; a rejected or void one gives it back.
enum class RefundStatus {
    PENDING_APPROVAL,
    COMPLETED,
    REJECTED,
    VOID;

    companion object {
        val HOLDING_CREDIT = setOf(PENDING_APPROVAL, COMPLETED)
    }
}

// Unused maintenance credit paid back to a flat.
@Entity
@Table(name = "maintenance_refund")
class MaintenanceRefund(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flat_id", nullable = false)
    var flat: Flat,

    @Column(name = "refund_number", nullable = false)
    var refundNumber: String,

    @Column(nullable = false, precision = 12, scale = 2)
    var amount: BigDecimal,

    @Column(name = "paid_on", nullable = false)
    var paidOn: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var method: PaymentMethod,

    @Column(nullable = false)
    var reason: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: RefundStatus,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,

    // The finance cash/bank ledger it was paid from; finance checks it when it posts the refund.
    @Column(name = "paid_from_account_id")
    var paidFromAccountId: Long? = null,

    var reference: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decided_by")
    var decidedBy: Long? = null

    @Column(name = "decision_reason")
    var decisionReason: String? = null

    @Column(name = "voided_at")
    var voidedAt: Instant? = null

    @Column(name = "voided_by")
    var voidedBy: Long? = null

    @Column(name = "void_reason")
    var voidReason: String? = null

    @OneToMany(mappedBy = "refund", cascade = [CascadeType.PERSIST], fetch = FetchType.LAZY)
    @OrderBy("id")
    var allocations: MutableList<RefundAllocation> = mutableListOf()

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}

// How much of one payment's credit a refund used.
@Entity
@Table(name = "maintenance_refund_allocation")
class RefundAllocation(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refund_id", nullable = false)
    var refund: MaintenanceRefund,

    @Column(name = "payment_id", nullable = false)
    var paymentId: Long,

    @Column(nullable = false, precision = 12, scale = 2)
    var amount: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
