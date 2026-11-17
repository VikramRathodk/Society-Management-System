package org.devvikram.societymanagement.subscription.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.subscription.dto.InvoiceResponse
import org.devvikram.societymanagement.subscription.dto.RazorpayVerifyRequest
import org.devvikram.societymanagement.subscription.service.CheckoutOrder
import org.devvikram.societymanagement.subscription.service.InvoiceService
import org.devvikram.societymanagement.subscription.service.OnlinePaymentService
import org.devvikram.societymanagement.subscription.service.SubscriptionService
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

// Paying an invoice online: "Pay now" creates a Razorpay order (SECRETARY and above, or the platform), the
// web opens Checkout with it, and comes back with Razorpay's signature to verify. The webhook is public
// (Razorpay calls it) and trusted only through its signature.
@RestController
class OnlinePaymentController(
    private val onlinePaymentService: OnlinePaymentService,
    private val invoiceService: InvoiceService,
    private val subscriptionService: SubscriptionService,
    private val accessService: AccessService,
) {
    @PostMapping("/api/platform-invoices/{id}/razorpay-order")
    fun order(@PathVariable id: Long): CheckoutOrder {
        val userId = payer(id)
        return onlinePaymentService.createOrder(id, userId)
    }

    @PostMapping("/api/platform-invoices/{id}/razorpay-verify")
    fun verify(@PathVariable id: Long, @Valid @RequestBody request: RazorpayVerifyRequest): InvoiceResponse {
        payer(id)
        val result = onlinePaymentService.verifyCheckout(id, request.orderId!!, request.paymentId!!, request.signature!!)
        result.problem?.let { throw ConflictException(it) }
        return InvoiceResponse.from(invoiceService.view(id), invoiceService.platform, subscriptionService.today())
    }

    // Razorpay's webhook (payment.captured, payment.failed). 400 on a bad signature; otherwise 200 with what
    // was done, so Razorpay stops retrying.
    @PostMapping("/api/webhooks/razorpay")
    fun webhook(
        @RequestBody body: String,
        @RequestHeader("X-Razorpay-Signature", required = false) signature: String?,
        @RequestHeader("X-Razorpay-Event-Id", required = false) eventId: String?,
    ): Map<String, String> = mapOf("outcome" to onlinePaymentService.webhook(body, signature, eventId))

    // The society's SECRETARY and above (SOCIETY.SUBSCRIPTION.UPDATE) or the platform.
    private fun payer(invoiceId: Long): Long {
        val access = accessService.current()
        val societyId = invoiceService.get(invoiceId).societyId
        if (!access.canSocietyWide(Permissions.SOCIETY_SUBSCRIPTION_UPDATE, societyId) && !access.canOnPlatform(Permissions.PLATFORM_BILLING_UPDATE)) {
            throw AccessDeniedException("Missing permission: ${Permissions.SOCIETY_SUBSCRIPTION_UPDATE} for this society")
        }
        return access.userId
    }
}
