package org.devvikram.societymanagement.subscription.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// Paid modules a plan may include; core modules (sign-in, structure, residents, visitors, notices,
// complaints) are in every plan. ALL also covers modules added later.
enum class PlanModule { MAINTENANCE, FINANCE, POLICIES, GOVERNANCE, ALL }

@Entity
@Table(name = "plan")
class Plan(
    @Column(nullable = false)
    var code: String,

    @Column(nullable = false)
    var name: String,

    var description: String?,

    @Column(name = "price_per_flat_monthly", nullable = false)
    var pricePerFlatMonthly: BigDecimal,

    @Column(name = "price_per_flat_yearly", nullable = false)
    var pricePerFlatYearly: BigDecimal,

    @Column(name = "minimum_monthly", nullable = false)
    var minimumMonthly: BigDecimal,

    @Column(name = "max_flats")
    var maxFlats: Int?,

    @Column(name = "trial_days", nullable = false)
    var trialDays: Int,

    // Comma-separated PlanModule names.
    @Column(nullable = false)
    var modules: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false)
    var active: Boolean = true

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0

    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    val moduleSet: Set<PlanModule>
        get() = modules.split(',').mapNotNull { m -> PlanModule.entries.firstOrNull { it.name == m.trim() } }.toSet()

    fun includes(module: PlanModule) = PlanModule.ALL in moduleSet || module in moduleSet
}

enum class BillingCycle { MONTHLY, YEARLY }

// TRIAL → ACTIVE → PAST_DUE (an invoice overdue) → SUSPENDED (unpaid after the grace period) → ACTIVE when
// paid; CANCELLED when the society leaves.
enum class SubscriptionStatus { TRIAL, ACTIVE, PAST_DUE, SUSPENDED, CANCELLED }

@Entity
@Table(name = "subscription")
class Subscription(
    @Column(name = "society_id", nullable = false)
    var societyId: Long,

    @Column(name = "plan_id", nullable = false)
    var planId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var cycle: BillingCycle,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SubscriptionStatus,

    @Column(name = "current_period_start", nullable = false)
    var currentPeriodStart: LocalDate,

    @Column(name = "current_period_end", nullable = false)
    var currentPeriodEnd: LocalDate,

    @Column(name = "next_invoice_on", nullable = false)
    var nextInvoiceOn: LocalDate,

    @Column(name = "created_by")
    var createdBy: Long?,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "trial_ends_on")
    var trialEndsOn: LocalDate? = null

    @Column(name = "pending_plan_id")
    var pendingPlanId: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_cycle")
    var pendingCycle: BillingCycle? = null

    @Column(name = "billing_contact_id")
    var billingContactId: Long? = null

    @Column(name = "discount_percent", nullable = false)
    var discountPercent: Int = 0

    @Column(name = "discount_periods_left", nullable = false)
    var discountPeriodsLeft: Int = 0

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
}

// What happened to a subscription.
enum class SubscriptionEventKind {
    STARTED, PLAN_CHANGE_SCHEDULED, PLAN_CHANGED, CYCLE_CHANGED, STATUS_CHANGED, DISCOUNT, CONTACT_CHANGED,
    CHANGE_REQUESTED, CANCELLED,
}

@Entity
@Table(name = "subscription_event")
class SubscriptionEvent(
    @Column(name = "subscription_id", nullable = false)
    var subscriptionId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: SubscriptionEventKind,

    @Column(name = "from_value")
    var fromValue: String?,

    @Column(name = "to_value")
    var toValue: String?,

    var note: String?,

    @Column(name = "created_by")
    var createdBy: Long?,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
