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
import java.math.BigDecimal
import java.time.Instant

// FIXED: `amount` per flat. PER_SQFT: `amount` is the rate per sq ft of the flat's area.
enum class ChargeCalcType { FIXED, PER_SQFT }

// One item on every flat's bill, e.g. Maintenance or Sinking fund. Inactive heads are skipped when billing.
@Entity
@Table(name = "maintenance_charge_head")
class ChargeHead(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "calc_type", nullable = false)
    var calcType: ChargeCalcType,

    @Column(nullable = false, precision = 12, scale = 2)
    var amount: BigDecimal,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    // Order of the lines on a bill.
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
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
