package org.devvikram.societymanagement.subscription.service

import org.devvikram.societymanagement.authorization.SocietyGate
import org.devvikram.societymanagement.common.exception.PaymentRequiredException
import org.devvikram.societymanagement.subscription.model.Plan
import org.devvikram.societymanagement.subscription.model.PlanModule
import org.devvikram.societymanagement.subscription.model.PlatformInvoice
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.subscription.repository.InvoiceRepository
import org.devvikram.societymanagement.subscription.repository.PlanRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder

// What a society's subscription allows: the plan's paid modules, and whether paid work is paused.
data class SocietyBillingState(
    val status: SubscriptionStatus,
    val plan: Plan,
    // The oldest unpaid invoice past its due date, for "pay invoice …".
    val overdue: PlatformInvoice?,
) {
    // Suspended (unpaid after the grace period) or cancelled (the society left): paid work is read-only.
    val readOnly: Boolean get() = status == SubscriptionStatus.SUSPENDED || status == SubscriptionStatus.CANCELLED
}

// The subscription's say on every society permission (see authorization.SocietyGate):
// - a permission of a paid module outside the plan answers 402 for reads and writes alike;
// - while suspended or cancelled, the paid modules' writes are paused (402 with "pay invoice …"), except
//   what residents do themselves (`app.billing.suspended-allowed`: acknowledging a policy, voting).
// Core modules (structure, residents, visitors, notices, complaints) and the subscription itself always
// work, so guards keep logging visitors and the society can still see and pay its invoices.
// Societies without a subscription (billing not switched on) are never gated.
@Component
class SubscriptionGate(
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: PlanRepository,
    private val invoiceRepository: InvoiceRepository,
    private val subscriptionService: SubscriptionService,
    @Value("\${app.billing.suspended-allowed:POLICY.ACKNOWLEDGEMENT.CREATE,GOVERNANCE.VOTE.CREATE}") allowed: String,
) : SocietyGate {
    private val allowedWhileSuspended = allowed.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    override fun check(societyId: Long, permission: String): String? {
        val module = moduleOf(permission) ?: return null
        val state = stateOf(societyId) ?: return null
        if (!state.plan.includes(module)) {
            throw PaymentRequiredException(
                "${label(module)} isn't part of the ${state.plan.name} plan. Ask the platform to upgrade from the Subscription page."
            )
        }
        if (state.readOnly && isWrite(permission) && permission !in allowedWhileSuspended) {
            return when {
                state.status == SubscriptionStatus.CANCELLED -> "The subscription is cancelled; the society's records are read-only"
                state.overdue != null ->
                    "Your subscription is suspended. Pay invoice ${state.overdue.number} (₹${state.overdue.balance.toPlainString()} due since ${state.overdue.dueOn}) to continue"
                else -> "Your subscription is suspended. Pay the overdue invoice to continue"
            }
        }
        return null
    }

    // The society's billing state, looked up once per request.
    fun stateOf(societyId: Long): SocietyBillingState? {
        val attributes = RequestContextHolder.getRequestAttributes()
        val key = "subscriptionGate.$societyId"
        (attributes?.getAttribute(key, RequestAttributes.SCOPE_REQUEST) as? Holder)?.let { return it.state }
        val state = load(societyId)
        attributes?.setAttribute(key, Holder(state), RequestAttributes.SCOPE_REQUEST)
        return state
    }

    private fun load(societyId: Long): SocietyBillingState? {
        val subscription = subscriptionRepository.findBySociety(societyId) ?: return null
        val plan = planRepository.findById(subscription.planId) ?: return null
        val today = subscriptionService.today()
        val unpaid = invoiceRepository.findBySociety(societyId).filter { it.balance.signum() > 0 }.sortedBy { it.dueOn }
        // A suspended society is pointed at its oldest unpaid invoice even if the run judged it on another day.
        val overdue = unpaid.firstOrNull { it.dueOn.isBefore(today) }
            ?: unpaid.firstOrNull()?.takeIf { subscription.status == SubscriptionStatus.SUSPENDED }
        return SocietyBillingState(subscription.status, plan, overdue)
    }

    private class Holder(val state: SocietyBillingState?)

    companion object {
        fun moduleOf(permission: String): PlanModule? = when (permission.substringBefore('.')) {
            "MAINTENANCE" -> PlanModule.MAINTENANCE
            "FINANCE" -> PlanModule.FINANCE
            "POLICY" -> PlanModule.POLICIES
            "GOVERNANCE" -> PlanModule.GOVERNANCE
            else -> null
        }

        // Reading and exporting stay open; everything else changes something.
        fun isWrite(permission: String): Boolean = permission.substringAfterLast('.') !in setOf("VIEW", "EXPORT")

        fun label(module: PlanModule): String = when (module) {
            PlanModule.MAINTENANCE -> "Maintenance billing"
            PlanModule.FINANCE -> "Finance and accounts"
            PlanModule.POLICIES -> "Policies"
            PlanModule.GOVERNANCE -> "Meetings and governance"
            PlanModule.ALL -> "Everything"
        }
    }
}
