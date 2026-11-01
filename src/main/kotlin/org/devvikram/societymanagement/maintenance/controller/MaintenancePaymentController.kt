package org.devvikram.societymanagement.maintenance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.common.export.Csv
import org.devvikram.societymanagement.common.export.ReportRange
import org.devvikram.societymanagement.maintenance.dto.CancelRequest
import org.devvikram.societymanagement.maintenance.dto.PaymentResponse
import org.devvikram.societymanagement.maintenance.dto.RecordPaymentRequest
import org.devvikram.societymanagement.maintenance.model.MaintenancePayment
import org.devvikram.societymanagement.maintenance.repository.PaymentSearch
import org.devvikram.societymanagement.maintenance.service.PaymentService
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
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
import java.time.LocalDate
import java.time.ZoneId

// Who sees and does what (all within one society):
//  - residents see their own flat's payments and receipts;
//  - society-wide roles see every payment; COMMITTEE and above export;
//  - SECRETARY and above (or a custom treasurer role) record and void payments.
@RestController
class MaintenancePaymentController(
    private val paymentService: PaymentService,
    private val accessService: AccessService,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {

    @PostMapping("/api/societies/{societyId}/maintenance/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.MAINTENANCE_PAYMENT_CREATE)
    fun record(@PathVariable societyId: Long, @Valid @RequestBody request: RecordPaymentRequest): PaymentResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_PAYMENT_CREATE, societyId)
        val id = paymentService.record(societyId, request.toCommand(), access.userId, LocalDate.now(reportZone))
        return PaymentResponse.from(paymentService.get(id), withAllocations = true)
    }

    // Newest first. Residents get their own flat(s) only; flatId narrows it; from/to are paid-on days.
    @GetMapping("/api/maintenance/payments")
    @RequiresPermission(Permissions.MAINTENANCE_PAYMENT_VIEW)
    fun payments(
        @RequestParam societyId: Long,
        @RequestParam(required = false) flatId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "true") includeVoid: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<PaymentResponse> {
        val access = accessService.current()
        access.requireIn(Permissions.MAINTENANCE_PAYMENT_VIEW, societyId)
        val flatIds = if (access.canSocietyWide(Permissions.MAINTENANCE_PAYMENT_VIEW, societyId)) {
            flatId?.let { setOf(it) }
        } else {
            val own = access.flatsWith(Permissions.MAINTENANCE_PAYMENT_VIEW)
            if (flatId != null && flatId !in own) throw AccessDeniedException("You can only see payments of your own flat")
            flatId?.let { setOf(it) } ?: own
        }
        val search = PaymentSearch(
            societyId = societyId,
            flatIds = flatIds,
            from = from,
            to = to,
            includeVoid = includeVoid,
            page = page.coerceAtLeast(0),
            size = size.coerceIn(1, 100),
        )
        return paymentService.search(search).map { PaymentResponse.from(it) }
    }

    // The receipt: the payment with the bills it paid.
    @GetMapping("/api/maintenance/payments/{id}")
    @RequiresPermission(Permissions.MAINTENANCE_PAYMENT_VIEW)
    fun payment(@PathVariable id: Long): PaymentResponse = PaymentResponse.from(viewable(id), withAllocations = true)

    @PostMapping("/api/maintenance/payments/{id}/void")
    @RequiresPermission(Permissions.MAINTENANCE_PAYMENT_UPDATE)
    fun void(@PathVariable id: Long, @Valid @RequestBody request: CancelRequest): PaymentResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_PAYMENT_UPDATE, paymentService.get(id).society.id!!)
        paymentService.void(id, access.userId, request.reason)
        return PaymentResponse.from(paymentService.get(id), withAllocations = true)
    }

    // Payments paid within [from, to] (void ones included, marked) as a CSV download.
    @GetMapping("/api/societies/{societyId}/maintenance/payments/export")
    @RequiresPermission(Permissions.MAINTENANCE_PAYMENT_EXPORT)
    fun export(
        @PathVariable societyId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<ByteArray> {
        requireSocietyWide(Permissions.MAINTENANCE_PAYMENT_EXPORT, societyId)
        val range = ReportRange.of(from, to, reportZone)
        val payments = paymentService.forExport(societyId, from, to)
        ReportRange.requireWithinLimit(payments.size)
        val csv = Csv.write(
            listOf(
                "receipt_number", "paid_on", "wing", "flat", "amount", "method", "reference", "bills", "status",
                "recorded_by", "recorded_at", "void_reason",
            ),
            payments.map { payment ->
                listOf(
                    payment.receiptNumber, payment.paidOn, payment.flat.wing.name, payment.flat.flatNumber,
                    payment.amount, payment.method, payment.reference,
                    payment.allocations.joinToString("; ") { "${it.bill.billNumber}: ${it.amount}" },
                    payment.status, payment.recordedBy.name, range.format(payment.recordedAt), payment.voidReason,
                )
            },
        )
        return Csv.download("maintenance-payments-$societyId-$from-to-$to.csv", csv)
    }

    private fun viewable(id: Long): MaintenancePayment {
        val payment = paymentService.get(id)
        if (!accessService.current().canOnFlat(Permissions.MAINTENANCE_PAYMENT_VIEW, payment.society.id!!, payment.flat.id!!)) {
            throw AccessDeniedException("You cannot see this payment")
        }
        return payment
    }

    private fun requireSocietyWide(permission: String, societyId: Long): Access = accessService.current().also {
        if (!it.canSocietyWide(permission, societyId)) {
            throw AccessDeniedException("Missing permission: $permission for this society")
        }
    }
}
