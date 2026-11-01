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

// UNPAID / PARTIAL / PAID follow the payments. WAIVED and CANCELLED are final and need a reason.
enum class BillStatus {
    UNPAID, PARTIAL, PAID, WAIVED, CANCELLED;

    companion object {
        // Bills that still expect money.
        val OPEN = setOf(UNPAID, PARTIAL)
    }
}

// FIXED / PER_SQFT come from charge heads; LATE_FEE and OPENING_BALANCE are added by the system.
enum class BillLineType { FIXED, PER_SQFT, LATE_FEE, OPENING_BALANCE }

// What one flat owes for one cycle. The lines are a copy of the charges at issue time.
@Entity
@Table(name = "maintenance_bill")
class MaintenanceBill(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    var cycle: MaintenanceCycle,

    // Always the cycle's society; stored so bills can be searched without joining the cycle.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flat_id", nullable = false)
    var flat: Flat,

    @Column(name = "bill_number", nullable = false)
    var billNumber: String,

    @Column(nullable = false, precision = 12, scale = 2)
    var total: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: BillStatus = BillStatus.UNPAID

    @Column(name = "status_reason")
    var statusReason: String? = null

    @Column(name = "status_changed_at")
    var statusChangedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_changed_by")
    var statusChangedBy: User? = null

    // Sum of allocations from payments that are not void.
    @Column(name = "amount_paid", nullable = false, precision = 12, scale = 2)
    var amountPaid: BigDecimal = BigDecimal.ZERO.setScale(2)

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @OneToMany(mappedBy = "bill", cascade = [CascadeType.PERSIST], fetch = FetchType.LAZY)
    @OrderBy("sortOrder")
    var lines: MutableList<MaintenanceBillLine> = mutableListOf()

    // What the flat still owes on this bill; zero once it is paid, waived or cancelled.
    val outstanding: BigDecimal
        get() = if (status in BillStatus.OPEN) total - amountPaid else BigDecimal.ZERO.setScale(2)

    // Sets amountPaid and, for bills still following payments, UNPAID / PARTIAL / PAID from it.
    // Waived and cancelled bills keep their status.
    fun applyPaidAmount(paid: BigDecimal) {
        amountPaid = paid
        if (status in BillStatus.OPEN || status == BillStatus.PAID) {
            status = when {
                paid.signum() == 0 -> BillStatus.UNPAID
                paid < total -> BillStatus.PARTIAL
                else -> BillStatus.PAID
            }
        }
    }

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}

@Entity
@Table(name = "maintenance_bill_line")
class MaintenanceBillLine(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    var bill: MaintenanceBill,

    @Column(name = "head_name", nullable = false)
    var headName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "calc_type", nullable = false)
    var calcType: BillLineType,

    @Column(nullable = false, precision = 12, scale = 2)
    var rate: BigDecimal,

    // 1 for FIXED, the flat's area for PER_SQFT.
    @Column(nullable = false, precision = 10, scale = 2)
    var quantity: BigDecimal,

    @Column(nullable = false, precision = 12, scale = 2)
    var amount: BigDecimal,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    // e.g. which bill a late charge is for and how it was worked out.
    @Column(length = 200)
    var note: String? = null,

    // LATE_FEE only: the overdue bill, and the day this charge covers up to (the next one starts there).
    @Column(name = "source_bill_id")
    var sourceBillId: Long? = null,

    @Column(name = "charged_through")
    var chargedThrough: LocalDate? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
