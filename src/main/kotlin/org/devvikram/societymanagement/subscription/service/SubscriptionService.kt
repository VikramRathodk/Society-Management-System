package org.devvikram.societymanagement.subscription.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.society.model.SocietyStatus
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietyWentLive
import org.devvikram.societymanagement.subscription.model.BillingCycle
import org.devvikram.societymanagement.subscription.model.Plan
import org.devvikram.societymanagement.subscription.model.PlanModule
import org.devvikram.societymanagement.subscription.model.Subscription
import org.devvikram.societymanagement.subscription.model.SubscriptionEvent
import org.devvikram.societymanagement.subscription.model.SubscriptionEventKind
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.subscription.repository.PlanRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionEventRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class PlanInput(
    val code: String,
    val name: String,
    val description: String? = null,
    val pricePerFlatMonthly: BigDecimal,
    val pricePerFlatYearly: BigDecimal,
    val minimumMonthly: BigDecimal = BigDecimal.ZERO,
    val maxFlats: Int? = null,
    val trialDays: Int = 30,
    val modules: Set<PlanModule> = emptySet(),
    val active: Boolean = true,
    val sortOrder: Int = 0,
)

// A subscription with its plans, flat count, the charge for a period at today's numbers, and history.
data class SubscriptionView(
    val subscription: Subscription,
    val plan: Plan,
    val pendingPlan: Plan?,
    val flats: Int,
    val chargePerPeriod: BigDecimal,
    val events: List<SubscriptionEvent>,
)

// Plans, each society's subscription and trial, and changes to it. Invoices and the billing run come
// later; this keeps the dates they will use (current period, next invoice).
@Service
class SubscriptionService(
    private val planRepository: PlanRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val eventRepository: SubscriptionEventRepository,
    private val societyService: SocietyService,
    private val flatRepository: FlatRepository,
    private val userRoleRepository: UserRoleRepository,
    private val clock: Clock,
    @Value("\${app.report.zone}") private val zone: ZoneId,
    // Off: no subscription starts by itself (platform billing not switched on yet).
    @Value("\${app.billing.enabled:false}") private val billingEnabled: Boolean,
    @Value("\${app.billing.default-plan:STANDARD}") private val defaultPlanCode: String,
) {
    fun today(): LocalDate = LocalDate.now(clock.withZone(zone))

    // ---- Plans ----

    fun plans(): List<Plan> = planRepository.findAll()

    fun plan(id: Long): Plan = planRepository.findById(id) ?: throw NoSuchElementException("Plan $id not found")

    @Transactional
    fun createPlan(input: PlanInput): Plan {
        val code = cleanCode(input.code)
        if (planRepository.findByCode(code) != null) throw ConflictException("There is already a plan $code")
        validate(input)
        return planRepository.save(
            Plan(
                code = code,
                name = input.name.trim(),
                description = input.description?.trim()?.ifEmpty { null },
                pricePerFlatMonthly = money(input.pricePerFlatMonthly),
                pricePerFlatYearly = money(input.pricePerFlatYearly),
                minimumMonthly = money(input.minimumMonthly),
                maxFlats = input.maxFlats,
                trialDays = input.trialDays,
                modules = input.modules.joinToString(",") { it.name },
            ).apply {
                active = input.active
                sortOrder = input.sortOrder
            }
        )
    }

    // Prices apply to invoices issued from now on; invoices already issued keep theirs.
    @Transactional
    fun updatePlan(id: Long, input: PlanInput): Plan {
        val plan = plan(id)
        val code = cleanCode(input.code)
        planRepository.findByCode(code)?.takeIf { it.id != id }?.let { throw ConflictException("There is already a plan $code") }
        validate(input)
        plan.code = code
        plan.name = input.name.trim()
        plan.description = input.description?.trim()?.ifEmpty { null }
        plan.pricePerFlatMonthly = money(input.pricePerFlatMonthly)
        plan.pricePerFlatYearly = money(input.pricePerFlatYearly)
        plan.minimumMonthly = money(input.minimumMonthly)
        plan.maxFlats = input.maxFlats
        plan.trialDays = input.trialDays
        plan.modules = input.modules.joinToString(",") { it.name }
        plan.active = input.active
        plan.sortOrder = input.sortOrder
        plan.updatedAt = Instant.now()
        return planRepository.save(plan)
    }

    // The charge for one period of this plan and cycle for this many flats: per flat, but never below the
    // minimum (12 months' minimum for a year).
    fun charge(plan: Plan, cycle: BillingCycle, flats: Int): BigDecimal = when (cycle) {
        BillingCycle.MONTHLY -> plan.pricePerFlatMonthly.multiply(BigDecimal(flats)).max(plan.minimumMonthly)
        BillingCycle.YEARLY -> plan.pricePerFlatYearly.multiply(BigDecimal(flats)).max(plan.minimumMonthly.multiply(BigDecimal(12)))
    }.setScale(2, RoundingMode.HALF_UP)

    // ---- Starting ----

    // A society that goes live starts its trial on the default plan, once billing is switched on.
    @EventListener
    fun onWentLive(event: SocietyWentLive) {
        if (!billingEnabled) return
        if (subscriptionRepository.findBySociety(event.societyId) != null) return
        val plan = planRepository.findByCode(defaultPlanCode) ?: return
        start(event.societyId, plan, BillingCycle.MONTHLY, null)
    }

    // Starts the trial for a live society (also how existing societies get theirs when billing begins).
    @Transactional
    fun startTrial(societyId: Long, planId: Long?, cycle: BillingCycle, userId: Long?): Subscription {
        val society = societyService.getById(societyId)
        if (society.status != SocietyStatus.LIVE) throw ConflictException("${society.name} isn't live yet; its trial starts when it goes live")
        if (subscriptionRepository.findBySociety(societyId) != null) throw ConflictException("${society.name} already has a subscription")
        val plan = planId?.let(::plan) ?: planRepository.findByCode(defaultPlanCode)
            ?: throw IllegalStateException("The default plan $defaultPlanCode doesn't exist")
        return start(societyId, plan, cycle, userId)
    }

    // Every live society without a subscription starts its trial on the default plan; answers how many.
    @Transactional
    fun startAll(userId: Long): Int {
        val plan = planRepository.findByCode(defaultPlanCode) ?: throw IllegalStateException("The default plan $defaultPlanCode doesn't exist")
        val started = societyService.getAll()
            .filter { it.status == SocietyStatus.LIVE && subscriptionRepository.findBySociety(it.id!!) == null }
        started.forEach { start(it.id!!, plan, BillingCycle.MONTHLY, userId) }
        return started.size
    }

    private fun start(societyId: Long, plan: Plan, cycle: BillingCycle, userId: Long?): Subscription {
        require(plan.active) { "The plan ${plan.name} isn't offered any more" }
        checkFlats(societyId, plan)
        val today = today()
        val trial = plan.trialDays > 0
        val periodEnd = if (trial) today.plusDays(plan.trialDays.toLong() - 1) else periodEnd(today, cycle)
        val subscription = subscriptionRepository.save(
            Subscription(
                societyId = societyId,
                planId = plan.id!!,
                cycle = cycle,
                status = if (trial) SubscriptionStatus.TRIAL else SubscriptionStatus.ACTIVE,
                currentPeriodStart = today,
                currentPeriodEnd = periodEnd,
                // A trial is followed by the first invoice; without one, the first period is invoiced today.
                nextInvoiceOn = if (trial) periodEnd.plusDays(1) else today,
                createdBy = userId,
            ).apply { trialEndsOn = if (trial) periodEnd else null }
        )
        record(subscription, SubscriptionEventKind.STARTED, null, "${plan.code} ${cycle.name}${if (trial) " trial until $periodEnd" else ""}", null, userId)
        return subscription
    }

    // ---- Changes ----

    // During a trial at once; otherwise from the next period (the next invoice uses it).
    @Transactional
    fun changePlan(societyId: Long, planId: Long?, cycle: BillingCycle?, userId: Long): Subscription {
        val subscription = lock(societyId)
        requireOpen(subscription)
        require(planId != null || cycle != null) { "Choose a plan, a billing cycle, or both" }
        val newPlan = planId?.let(::plan)
        newPlan?.let {
            require(it.active) { "The plan ${it.name} isn't offered any more" }
            checkFlats(societyId, it)
        }
        val fromText = "${plan(subscription.planId).code} ${subscription.cycle}"
        if (subscription.status == SubscriptionStatus.TRIAL) {
            newPlan?.let { subscription.planId = it.id!! }
            cycle?.let { subscription.cycle = it }
            subscription.pendingPlanId = null
            subscription.pendingCycle = null
            subscription.updatedAt = Instant.now()
            subscriptionRepository.save(subscription)
            record(subscription, SubscriptionEventKind.PLAN_CHANGED, fromText, "${plan(subscription.planId).code} ${subscription.cycle}", null, userId)
        } else {
            subscription.pendingPlanId = newPlan?.id?.takeIf { it != subscription.planId }
            subscription.pendingCycle = cycle?.takeIf { it != subscription.cycle }
            subscription.updatedAt = Instant.now()
            subscriptionRepository.save(subscription)
            val to = "${(subscription.pendingPlanId?.let(::plan) ?: plan(subscription.planId)).code} ${subscription.pendingCycle ?: subscription.cycle}"
            record(subscription, SubscriptionEventKind.PLAN_CHANGE_SCHEDULED, fromText, to, "From ${subscription.nextInvoiceOn}", userId)
        }
        return subscription
    }

    // A percentage off the next invoices.
    @Transactional
    fun setDiscount(societyId: Long, percent: Int, periods: Int, note: String?, userId: Long): Subscription {
        require(percent in 0..100) { "The discount is a percentage from 0 to 100" }
        require(periods in 0..120) { "The discount lasts 0 to 120 periods" }
        val subscription = lock(societyId)
        requireOpen(subscription)
        val from = "${subscription.discountPercent}% × ${subscription.discountPeriodsLeft}"
        subscription.discountPercent = if (periods == 0) 0 else percent
        subscription.discountPeriodsLeft = if (percent == 0) 0 else periods
        subscription.updatedAt = Instant.now()
        subscriptionRepository.save(subscription)
        record(subscription, SubscriptionEventKind.DISCOUNT, from, "${subscription.discountPercent}% × ${subscription.discountPeriodsLeft}", cleanNote(note), userId)
        return subscription
    }

    // The person invoices and reminders go to: someone with a society-wide role in the society.
    @Transactional
    fun setBillingContact(societyId: Long, contactId: Long, userId: Long): Subscription {
        val subscription = lock(societyId)
        val roles = userRoleRepository.findByUserId(contactId)
        require(roles.any { it.society?.id == societyId && it.flat == null && it.user.isActive }) {
            "The billing contact must be an active member of the society's committee or staff"
        }
        val from = subscription.billingContactId?.toString()
        subscription.billingContactId = contactId
        subscription.updatedAt = Instant.now()
        subscriptionRepository.save(subscription)
        record(subscription, SubscriptionEventKind.CONTACT_CHANGED, from, contactId.toString(), null, userId)
        return subscription
    }

    // The society asks the platform for another plan or cycle; the super admin decides.
    @Transactional
    fun requestChange(societyId: Long, planId: Long?, cycle: BillingCycle?, note: String?, userId: Long): Subscription {
        val subscription = lock(societyId)
        requireOpen(subscription)
        require(planId != null || cycle != null) { "Choose a plan, a billing cycle, or both" }
        val to = "${(planId?.let(::plan) ?: plan(subscription.planId)).code} ${cycle ?: subscription.cycle}"
        record(subscription, SubscriptionEventKind.CHANGE_REQUESTED, "${plan(subscription.planId).code} ${subscription.cycle}", to, cleanNote(note), userId)
        return subscription
    }

    @Transactional
    fun cancel(societyId: Long, note: String, userId: Long): Subscription {
        val subscription = lock(societyId)
        requireOpen(subscription)
        val from = subscription.status.name
        subscription.status = SubscriptionStatus.CANCELLED
        subscription.cancelledAt = Instant.now()
        subscription.updatedAt = Instant.now()
        subscriptionRepository.save(subscription)
        record(subscription, SubscriptionEventKind.CANCELLED, from, SubscriptionStatus.CANCELLED.name, requireNotNull(cleanNote(note)) { "Say why" }, userId)
        return subscription
    }

    // ---- Reads ----

    @Transactional(readOnly = true)
    fun view(societyId: Long): SubscriptionView? {
        val subscription = subscriptionRepository.findBySociety(societyId) ?: return null
        val plan = plan(subscription.planId)
        val flats = flatCount(societyId)
        return SubscriptionView(
            subscription = subscription,
            plan = plan,
            pendingPlan = subscription.pendingPlanId?.let(::plan),
            flats = flats,
            chargePerPeriod = charge(subscription.pendingPlanId?.let(::plan) ?: plan, subscription.pendingCycle ?: subscription.cycle, flats),
            events = eventRepository.findBySubscription(subscription.id!!),
        )
    }

    @Transactional(readOnly = true)
    fun all(): List<SubscriptionView> = subscriptionRepository.findAll().mapNotNull { view(it.societyId) }

    // The paid modules the society may use now (core modules are always allowed); null without billing.
    fun modulesOf(societyId: Long): Set<PlanModule>? {
        val subscription = subscriptionRepository.findBySociety(societyId) ?: return null
        return plan(subscription.planId).moduleSet
    }

    fun flatCount(societyId: Long): Int = flatRepository.findBySocietyId(societyId).size

    // ---- Helpers ----

    // The last day of a period starting on `start`.
    fun periodEnd(start: LocalDate, cycle: BillingCycle): LocalDate = when (cycle) {
        BillingCycle.MONTHLY -> start.plusMonths(1).minusDays(1)
        BillingCycle.YEARLY -> start.plusYears(1).minusDays(1)
    }

    private fun lock(societyId: Long): Subscription =
        subscriptionRepository.lockBySociety(societyId) ?: throw NoSuchElementException("Society $societyId has no subscription")

    private fun requireOpen(subscription: Subscription) {
        if (subscription.status == SubscriptionStatus.CANCELLED) throw ConflictException("The subscription is cancelled")
    }

    private fun checkFlats(societyId: Long, plan: Plan) {
        val max = plan.maxFlats ?: return
        val flats = flatCount(societyId)
        require(flats <= max) { "The plan ${plan.name} is for up to $max flats; the society has $flats" }
    }

    private fun record(subscription: Subscription, kind: SubscriptionEventKind, from: String?, to: String?, note: String?, userId: Long?) {
        eventRepository.save(SubscriptionEvent(subscription.id!!, kind, from, to, note, userId))
    }

    private fun validate(input: PlanInput) {
        require(input.name.isNotBlank() && input.name.trim().length <= 100) { "A plan needs a name of at most 100 characters" }
        listOf(input.pricePerFlatMonthly, input.pricePerFlatYearly, input.minimumMonthly).forEach {
            require(it >= BigDecimal.ZERO && it < BigDecimal(100_000)) { "Prices are from 0 to 99,999.99 rupees" }
        }
        require(input.trialDays in 0..365) { "A trial lasts 0 to 365 days" }
        require(input.maxFlats == null || input.maxFlats > 0) { "Max flats must be positive (or empty for no limit)" }
        require((input.description?.length ?: 0) <= 1000) { "The description can be at most 1,000 characters" }
    }

    private fun cleanCode(code: String): String {
        val clean = code.trim().uppercase()
        require(Regex("[A-Z][A-Z0-9_]{1,29}").matches(clean)) { "A plan code is 2–30 letters, digits or _ starting with a letter" }
        return clean
    }

    private fun cleanNote(note: String?): String? {
        val clean = note?.trim()?.ifEmpty { null } ?: return null
        require(clean.length <= 500) { "The note can be at most 500 characters" }
        return clean
    }

    private fun money(value: BigDecimal) = value.setScale(2, RoundingMode.HALF_UP)
}
