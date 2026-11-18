package org.devvikram.societymanagement.subscription.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.subscription.dto.BillingContactRequest
import org.devvikram.societymanagement.subscription.dto.CancelSubscriptionRequest
import org.devvikram.societymanagement.subscription.dto.ChangePlanRequest
import org.devvikram.societymanagement.subscription.dto.DiscountRequest
import org.devvikram.societymanagement.subscription.dto.PlanRecommendationResponse
import org.devvikram.societymanagement.subscription.dto.PlanRequest
import org.devvikram.societymanagement.subscription.dto.PlanResponse
import org.devvikram.societymanagement.subscription.dto.StartAllResponse
import org.devvikram.societymanagement.subscription.dto.StartTrialRequest
import org.devvikram.societymanagement.subscription.dto.SubscriptionResponse
import org.devvikram.societymanagement.subscription.dto.SubscriptionAccessResponse
import org.devvikram.societymanagement.subscription.model.PlanModule
import org.devvikram.societymanagement.subscription.service.SubscriptionGate
import org.devvikram.societymanagement.subscription.service.SubscriptionService
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Platform billing (SUPER_ADMIN: PLATFORM.BILLING.VIEW / UPDATE): plans, every subscription, starting
// trials, plan changes, discounts, cancelling. A society's own subscription (SECRETARY and above:
// SOCIETY.SUBSCRIPTION.VIEW / UPDATE): read it, set the billing contact, ask for a change.
@RestController
class SubscriptionController(
    private val subscriptionService: SubscriptionService,
    private val societyService: SocietyService,
    private val accessService: AccessService,
    private val gate: SubscriptionGate,
) {
    // ---- Plans ----

    // Every signed-in user may see the plans on offer (the society page compares them).
    @GetMapping("/api/plans")
    fun plans(): List<PlanResponse> {
        val access = accessService.current()
        return subscriptionService.plans().filter { it.active || access.canOnPlatform(Permissions.PLATFORM_BILLING_VIEW) }.map(PlanResponse::from)
    }

    // Public (the landing page's pricing): the plans on offer, no sign-in needed.
    @GetMapping("/api/public/plans")
    fun publicPlans(): List<PlanResponse> = subscriptionService.plans().filter { it.active }.map(PlanResponse::from)

    // Public (the landing page's "How many flats?"): every plan's cost for that many flats and the recommended one.
    @GetMapping("/api/public/plans/quote")
    fun quote(@RequestParam flats: Int): PlanRecommendationResponse =
        PlanRecommendationResponse.from(subscriptionService.recommend(flats))

    @PostMapping("/api/platform/plans")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPlan(@Valid @RequestBody request: PlanRequest): PlanResponse {
        platform(Permissions.PLATFORM_BILLING_UPDATE)
        return PlanResponse.from(subscriptionService.createPlan(request.toInput()))
    }

    @PutMapping("/api/platform/plans/{id}")
    fun updatePlan(@PathVariable id: Long, @Valid @RequestBody request: PlanRequest): PlanResponse {
        platform(Permissions.PLATFORM_BILLING_UPDATE)
        return PlanResponse.from(subscriptionService.updatePlan(id, request.toInput()))
    }

    // ---- Subscriptions (platform) ----

    @GetMapping("/api/platform/subscriptions")
    fun all(): List<SubscriptionResponse> {
        platform(Permissions.PLATFORM_BILLING_VIEW)
        val names = societyService.getAll().associate { it.id!! to it.name }
        return subscriptionService.all().map { SubscriptionResponse.from(it, names[it.subscription.societyId]) }
    }

    // Starts the trial for every live society without a subscription (billing switched on).
    @PostMapping("/api/platform/subscriptions/start-all")
    fun startAll(): StartAllResponse {
        val access = platform(Permissions.PLATFORM_BILLING_UPDATE)
        return StartAllResponse(subscriptionService.startAll(access.userId))
    }

    @PostMapping("/api/societies/{societyId}/subscription")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@PathVariable societyId: Long, @RequestBody(required = false) request: StartTrialRequest?): SubscriptionResponse {
        val access = platform(Permissions.PLATFORM_BILLING_UPDATE)
        val r = request ?: StartTrialRequest()
        subscriptionService.startTrial(societyId, r.planId, r.cycle, access.userId)
        return view(societyId)
    }

    @PatchMapping("/api/societies/{societyId}/subscription/plan")
    fun changePlan(@PathVariable societyId: Long, @RequestBody request: ChangePlanRequest): SubscriptionResponse {
        val access = platform(Permissions.PLATFORM_BILLING_UPDATE)
        subscriptionService.changePlan(societyId, request.planId, request.cycle, access.userId)
        return view(societyId)
    }

    @PutMapping("/api/societies/{societyId}/subscription/discount")
    fun discount(@PathVariable societyId: Long, @Valid @RequestBody request: DiscountRequest): SubscriptionResponse {
        val access = platform(Permissions.PLATFORM_BILLING_UPDATE)
        subscriptionService.setDiscount(societyId, request.percent!!, request.periods!!, request.note, access.userId)
        return view(societyId)
    }

    @PostMapping("/api/societies/{societyId}/subscription/cancel")
    fun cancel(@PathVariable societyId: Long, @Valid @RequestBody request: CancelSubscriptionRequest): SubscriptionResponse {
        val access = platform(Permissions.PLATFORM_BILLING_UPDATE)
        subscriptionService.cancel(societyId, request.note!!, access.userId)
        return view(societyId)
    }

    // ---- The society's own ----

    @GetMapping("/api/societies/{societyId}/subscription")
    fun get(@PathVariable societyId: Long): SubscriptionResponse {
        society(Permissions.SOCIETY_SUBSCRIPTION_VIEW, societyId)
        return view(societyId)
    }

    // Every plan's cost at the society's flat count today, and the plan recommended for it.
    @GetMapping("/api/societies/{societyId}/subscription/recommendation")
    fun recommendation(@PathVariable societyId: Long): PlanRecommendationResponse {
        society(Permissions.SOCIETY_SUBSCRIPTION_VIEW, societyId)
        societyService.getById(societyId)
        return PlanRecommendationResponse.from(subscriptionService.recommendFor(societyId))
    }

    @PutMapping("/api/societies/{societyId}/subscription/billing-contact")
    fun contact(@PathVariable societyId: Long, @Valid @RequestBody request: BillingContactRequest): SubscriptionResponse {
        val access = society(Permissions.SOCIETY_SUBSCRIPTION_UPDATE, societyId)
        subscriptionService.setBillingContact(societyId, request.userId!!, access.userId)
        return view(societyId)
    }

    @PostMapping("/api/societies/{societyId}/subscription/request-change")
    fun requestChange(@PathVariable societyId: Long, @RequestBody request: ChangePlanRequest): SubscriptionResponse {
        val access = society(Permissions.SOCIETY_SUBSCRIPTION_UPDATE, societyId)
        subscriptionService.requestChange(societyId, request.planId, request.cycle, request.note, access.userId)
        return view(societyId)
    }

    // Any member of the society: which paid modules to show and whether paid work is paused (the banner).
    @GetMapping("/api/societies/{societyId}/subscription/access")
    fun access(@PathVariable societyId: Long): SubscriptionAccessResponse {
        val access = accessService.current()
        if (access.grants.none { it.societyId == societyId } && !access.canOnPlatform(Permissions.PLATFORM_BILLING_VIEW)) {
            throw AccessDeniedException("You are not a member of this society")
        }
        val state = gate.stateOf(societyId)
            ?: return SubscriptionAccessResponse(false, null, null, PAID_MODULES, false, null, null, null, null, null)
        return SubscriptionAccessResponse(
            billing = true,
            status = state.status,
            planName = state.plan.name,
            modules = PAID_MODULES.filter(state.plan::includes).toSet(),
            readOnly = state.readOnly,
            trialEndsOn = subscriptionService.view(societyId)?.subscription?.trialEndsOn,
            overdueInvoiceId = state.overdue?.id,
            overdueInvoiceNumber = state.overdue?.number,
            overdueBalance = state.overdue?.balance,
            overdueSince = state.overdue?.dueOn,
        )
    }

    // ---- Helpers ----

    private fun view(societyId: Long): SubscriptionResponse {
        val v = subscriptionService.view(societyId) ?: throw NoSuchElementException("Society $societyId has no subscription")
        return SubscriptionResponse.from(v, societyService.getById(societyId).name)
    }

    private fun platform(permission: String): Access {
        val access = accessService.current()
        access.requireOnPlatform(permission)
        return access
    }

    // The society's own permission, or the platform's billing permission.
    private fun society(permission: String, societyId: Long): Access {
        val access = accessService.current()
        val platform = if (permission == Permissions.SOCIETY_SUBSCRIPTION_VIEW) Permissions.PLATFORM_BILLING_VIEW else Permissions.PLATFORM_BILLING_UPDATE
        if (!access.canSocietyWide(permission, societyId) && !access.canOnPlatform(platform)) {
            throw AccessDeniedException("Missing permission: $permission for this society")
        }
        return access
    }

    companion object {
        private val PAID_MODULES = PlanModule.entries.filter { it != PlanModule.ALL }.toSet()
    }
}
