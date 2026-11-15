package org.devvikram.societymanagement.subscription.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.subscription.dto.CreditNoteRequest
import org.devvikram.societymanagement.subscription.dto.InvoiceResponse
import org.devvikram.societymanagement.subscription.dto.InvoiceSummaryResponse
import org.devvikram.societymanagement.subscription.dto.RecordPaymentRequest
import org.devvikram.societymanagement.subscription.model.InvoiceStatus
import org.devvikram.societymanagement.subscription.service.InvoiceService
import org.devvikram.societymanagement.subscription.service.SubscriptionService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// The platform's invoices: the super admin issues what is due, records payments and credit notes, and
// sees every invoice; a society (SECRETARY and above) sees its own.
@RestController
class InvoiceController(
    private val invoiceService: InvoiceService,
    private val subscriptionService: SubscriptionService,
    private val accessService: AccessService,
) {
    // The invoice for the next period if it is due today or earlier; 204 when nothing is due yet. The
    // daily billing run does the same for every society.
    @PostMapping("/api/societies/{societyId}/subscription/invoices/issue-due")
    fun issueDue(@PathVariable societyId: Long): ResponseEntity<InvoiceResponse> {
        val access = platform(Permissions.PLATFORM_BILLING_UPDATE)
        val invoice = invoiceService.issueDue(societyId, access.userId, subscriptionService.today()) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.status(HttpStatus.CREATED).body(view(invoice.id!!))
    }

    @GetMapping("/api/societies/{societyId}/subscription/invoices")
    fun ofSociety(@PathVariable societyId: Long): List<InvoiceSummaryResponse> {
        society(societyId)
        val today = subscriptionService.today()
        return invoiceService.ofSociety(societyId).map { InvoiceSummaryResponse.from(it, today) }
    }

    // Every invoice; status=ISSUED|PARTLY_PAID|PAID, overdue=true for unpaid past their due date.
    @GetMapping("/api/platform/invoices")
    fun all(
        @RequestParam(required = false) status: InvoiceStatus?,
        @RequestParam(defaultValue = "false") overdue: Boolean,
    ): List<InvoiceSummaryResponse> {
        platform(Permissions.PLATFORM_BILLING_VIEW)
        val today = subscriptionService.today()
        return invoiceService.all()
            .map { InvoiceSummaryResponse.from(it, today) }
            .filter { status == null || it.status == status }
            .filter { !overdue || it.overdue }
    }

    @GetMapping("/api/platform-invoices/{id}")
    fun get(@PathVariable id: Long): InvoiceResponse {
        society(invoiceService.get(id).societyId)
        return view(id)
    }

    @PostMapping("/api/platform-invoices/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun pay(@PathVariable id: Long, @Valid @RequestBody request: RecordPaymentRequest): InvoiceResponse {
        val access = platform(Permissions.PLATFORM_BILLING_UPDATE)
        invoiceService.recordPayment(
            id, request.amount!!, request.mode!!, request.reference, request.paidOn ?: subscriptionService.today(), access.userId,
        )
        return view(id)
    }

    @PostMapping("/api/platform-invoices/{id}/credit-notes")
    @ResponseStatus(HttpStatus.CREATED)
    fun credit(@PathVariable id: Long, @Valid @RequestBody request: CreditNoteRequest): InvoiceResponse {
        val access = platform(Permissions.PLATFORM_BILLING_UPDATE)
        invoiceService.creditNote(id, request.amount!!, request.reason!!, access.userId)
        return view(id)
    }

    private fun view(id: Long) = InvoiceResponse.from(invoiceService.view(id), invoiceService.platform, subscriptionService.today())

    private fun platform(permission: String): Access {
        val access = accessService.current()
        access.requireOnPlatform(permission)
        return access
    }

    private fun society(societyId: Long): Access {
        val access = accessService.current()
        if (!access.canSocietyWide(Permissions.SOCIETY_SUBSCRIPTION_VIEW, societyId) && !access.canOnPlatform(Permissions.PLATFORM_BILLING_VIEW)) {
            throw AccessDeniedException("Missing permission: ${Permissions.SOCIETY_SUBSCRIPTION_VIEW} for this society")
        }
        return access
    }
}
