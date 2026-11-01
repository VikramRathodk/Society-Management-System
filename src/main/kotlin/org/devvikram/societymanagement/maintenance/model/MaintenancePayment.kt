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
import org.devvikram.societymanagement.user.model.User
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class PaymentMethod { CASH, CHEQUE, UPI, BANK_TRANSFER }

// A VOID payment no longer counts towards any bill; its allocations are kept as history.
enum class PaymentStatus { RECORDED, VOID }

// Money a flat paid the society offline, split across its bills.
@Entity
@Table(name = "maintenance_payment")
class MaintenancePayment(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flat_id", nullable = false)
    var flat: Flat,

    @Column(name = "receipt_number", nullable = false)
    var receiptNumber: String,

    @Column(nullable = false, precision = 12, scale = 2)
    var amount: BigDecimal,

    @Column(name = "paid_on", nullable = false)
    var paidOn: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var method: PaymentMethod,

    var reference: String? = null,

    var note: String? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by", nullable = false)
    var recordedBy: User,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentStatus = PaymentStatus.RECORDED

    @Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant? = null

    @Column(name = "voided_at")
    var voidedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voided_by")
    var voidedBy: User? = null

    @Column(name = "void_reason")
    var voidReason: String? = null

    @OneToMany(mappedBy = "payment", cascade = [CascadeType.PERSIST], fetch = FetchType.LAZY)
    @OrderBy("id")
    var allocations: MutableList<PaymentAllocation> = mutableListOf()

    @PrePersist
    fun onCreate() {
        if (recordedAt == null) recordedAt = Instant.now()
    }
}

@Entity
@Table(name = "maintenance_payment_allocation")
class PaymentAllocation(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    var payment: MaintenancePayment,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    var bill: MaintenanceBill,

    @Column(nullable = false, precision = 12, scale = 2)
    var amount: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
