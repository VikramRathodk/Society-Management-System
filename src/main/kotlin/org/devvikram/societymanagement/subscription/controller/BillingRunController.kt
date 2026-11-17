package org.devvikram.societymanagement.subscription.controller

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.subscription.service.BillingRunResult
import org.devvikram.societymanagement.subscription.service.BillingRunService
import org.devvikram.societymanagement.subscription.service.SubscriptionService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

// The daily billing run on demand (super admin): the same pass the 06:00 job makes, for today. Safe to
// repeat — nothing is invoiced or reminded twice.
@RestController
class BillingRunController(
    private val billingRun: BillingRunService,
    private val subscriptionService: SubscriptionService,
    private val accessService: AccessService,
) {
    @PostMapping("/api/platform/billing/run")
    fun run(): BillingRunResult {
        accessService.current().requireOnPlatform(Permissions.PLATFORM_BILLING_UPDATE)
        return billingRun.run(subscriptionService.today())
    }
}
