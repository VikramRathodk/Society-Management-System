package org.devvikram.societymanagement.maintenance.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.devvikram.societymanagement.user.model.User
import java.math.BigDecimal
import java.time.Instant

enum class BillingFrequency(val months: Long) {
    MONTHLY(1), QUARTERLY(3), HALF_YEARLY(6), YEARLY(12)
}

// How an overdue bill is charged on the next cycle. FIXED: `lateFeeValue` rupees once per overdue bill.
// SIMPLE_INTEREST: `lateFeeValue` % a year on the outstanding amount, for the days since it was due.
enum class LateFeeType { NONE, FIXED, SIMPLE_INTEREST }

// A society's billing configuration. A society without a row uses the defaults below.
@Entity
@Table(name = "maintenance_settings")
class MaintenanceSettings(
    @Id
    @Column(name = "society_id")
    var societyId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_frequency", nullable = false)
    var billingFrequency: BillingFrequency = BillingFrequency.MONTHLY,

    @Column(name = "due_days", nullable = false)
    var dueDays: Int = 15,

    @Column(name = "round_to_rupee", nullable = false)
    var roundToRupee: Boolean = true,

    @Enumerated(EnumType.STRING)
    @Column(name = "late_fee_type", nullable = false)
    var lateFeeType: LateFeeType = LateFeeType.NONE,

    @Column(name = "late_fee_value", nullable = false, precision = 12, scale = 2)
    var lateFeeValue: BigDecimal = BigDecimal.ZERO.setScale(2),

    // A bill is overdue only once this many days have passed after its due date.
    @Column(name = "grace_days", nullable = false)
    var graceDays: Int = 0,
) {
    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    var updatedBy: User? = null
}
