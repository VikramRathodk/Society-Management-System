package org.devvikram.societymanagement.subscription.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.devvikram.societymanagement.subscription.model.BillingCycle
import org.devvikram.societymanagement.subscription.model.Plan
import org.devvikram.societymanagement.subscription.model.PlanModule
import org.devvikram.societymanagement.subscription.model.SubscriptionEventKind
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.subscription.service.PlanInput
import org.devvikram.societymanagement.subscription.service.SubscriptionView
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class PlanRequest(
    @field:NotBlank val code: String?,
    @field:NotBlank val name: String?,
    val description: String? = null,
    @field:NotNull val pricePerFlatMonthly: BigDecimal?,
    @field:NotNull val pricePerFlatYearly: BigDecimal?,
    val minimumMonthly: BigDecimal = BigDecimal.ZERO,
    val maxFlats: Int? = null,
    val trialDays: Int = 30,
    val modules: Set<PlanModule> = emptySet(),
    val active: Boolean = true,
    val sortOrder: Int = 0,
) {
    fun toInput() = PlanInput(
        code!!, name!!, description, pricePerFlatMonthly!!, pricePerFlatYearly!!, minimumMonthly, maxFlats, trialDays, modules, active, sortOrder,
    )
}

data class PlanResponse(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
    val pricePerFlatMonthly: BigDecimal,
    val pricePerFlatYearly: BigDecimal,
    val minimumMonthly: BigDecimal,
    val maxFlats: Int?,
    val trialDays: Int,
    val modules: Set<PlanModule>,
    val active: Boolean,
    val sortOrder: Int,
) {
    companion object {
        fun from(p: Plan) = PlanResponse(
            p.id!!, p.code, p.name, p.description, p.pricePerFlatMonthly, p.pricePerFlatYearly, p.minimumMonthly,
            p.maxFlats, p.trialDays, p.moduleSet, p.active, p.sortOrder,
        )
    }
}

data class StartTrialRequest(val planId: Long? = null, val cycle: BillingCycle = BillingCycle.MONTHLY)

data class ChangePlanRequest(val planId: Long? = null, val cycle: BillingCycle? = null, val note: String? = null)

data class DiscountRequest(@field:NotNull val percent: Int?, @field:NotNull val periods: Int?, val note: String? = null)

data class BillingContactRequest(@field:NotNull val userId: Long?)

data class CancelSubscriptionRequest(@field:NotBlank val note: String?)

data class StartAllResponse(val started: Int)

data class SubscriptionEventResponse(
    val kind: SubscriptionEventKind,
    val from: String?,
    val to: String?,
    val note: String?,
    val by: Long?,
    val at: Instant,
)

data class SubscriptionResponse(
    val societyId: Long,
    val societyName: String?,
    val plan: PlanResponse,
    val cycle: BillingCycle,
    val status: SubscriptionStatus,
    val trialEndsOn: LocalDate?,
    val currentPeriodStart: LocalDate,
    val currentPeriodEnd: LocalDate,
    val nextInvoiceOn: LocalDate,
    // Applies from the next period.
    val pendingPlan: PlanResponse?,
    val pendingCycle: BillingCycle?,
    val billingContactId: Long?,
    val discountPercent: Int,
    val discountPeriodsLeft: Int,
    val flats: Int,
    // One period at today's flat count, with the next period's plan and cycle (before discount and tax).
    val chargePerPeriod: BigDecimal,
    val cancelledAt: Instant?,
    val events: List<SubscriptionEventResponse>,
) {
    companion object {
        fun from(v: SubscriptionView, societyName: String?) = SubscriptionResponse(
            societyId = v.subscription.societyId,
            societyName = societyName,
            plan = PlanResponse.from(v.plan),
            cycle = v.subscription.cycle,
            status = v.subscription.status,
            trialEndsOn = v.subscription.trialEndsOn,
            currentPeriodStart = v.subscription.currentPeriodStart,
            currentPeriodEnd = v.subscription.currentPeriodEnd,
            nextInvoiceOn = v.subscription.nextInvoiceOn,
            pendingPlan = v.pendingPlan?.let(PlanResponse::from),
            pendingCycle = v.subscription.pendingCycle,
            billingContactId = v.subscription.billingContactId,
            discountPercent = v.subscription.discountPercent,
            discountPeriodsLeft = v.subscription.discountPeriodsLeft,
            flats = v.flats,
            chargePerPeriod = v.chargePerPeriod,
            cancelledAt = v.subscription.cancelledAt,
            events = v.events.map { SubscriptionEventResponse(it.kind, it.fromValue, it.toValue, it.note, it.createdBy, it.createdAt) },
        )
    }
}

// What the society's members' app needs to know: which paid modules to show, and whether to show the
// "suspended — pay invoice …" banner. Without billing, everything is on.
data class SubscriptionAccessResponse(
    val billing: Boolean,
    val status: SubscriptionStatus?,
    val planName: String?,
    // The paid modules the society may use (ALL expanded); core modules are always on.
    val modules: Set<PlanModule>,
    val readOnly: Boolean,
    val trialEndsOn: LocalDate?,
    val overdueInvoiceId: Long?,
    val overdueInvoiceNumber: String?,
    val overdueBalance: BigDecimal?,
    val overdueSince: LocalDate?,
)
