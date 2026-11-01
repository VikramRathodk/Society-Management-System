package org.devvikram.societymanagement.maintenance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.common.export.Csv
import org.devvikram.societymanagement.common.export.ReportRange
import org.devvikram.societymanagement.maintenance.dto.BillResponse
import org.devvikram.societymanagement.maintenance.dto.BillingPeriodRequest
import org.devvikram.societymanagement.maintenance.dto.BillingPeriodResponse
import org.devvikram.societymanagement.maintenance.dto.BillingPreviewResponse
import org.devvikram.societymanagement.maintenance.dto.CancelRequest
import org.devvikram.societymanagement.maintenance.dto.CycleResponse
import org.devvikram.societymanagement.maintenance.dto.OpeningBalancesRequest
import org.devvikram.societymanagement.maintenance.model.BillStatus
import org.devvikram.societymanagement.maintenance.model.MaintenanceBill
import org.devvikram.societymanagement.maintenance.repository.BillSearch
import org.devvikram.societymanagement.maintenance.service.BillingService
import org.devvikram.societymanagement.maintenance.service.PaymentService
import org.springframework.beans.factory.annotation.Value
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
//  - residents see their own flat's bills (with lines);
//  - society-wide roles see every cycle and bill; COMMITTEE and above export;
//  - SECRETARY and above preview and issue cycles, cancel a cycle, and waive a bill.
@RestController
class MaintenanceBillingController(
    private val billingService: BillingService,
    private val paymentService: PaymentService,
    private val accessService: AccessService,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {

    // The period to bill next, from the latest issued cycle and the society's billing frequency.
    @GetMapping("/api/societies/{societyId}/maintenance/cycles/next")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_CREATE)
    fun nextPeriod(@PathVariable societyId: Long): BillingPeriodResponse {
        requireSocietyWide(Permissions.MAINTENANCE_BILL_CREATE, societyId)
        return BillingPeriodResponse.from(billingService.suggestNextPeriod(societyId, LocalDate.now(reportZone)))
    }

    // Every flat's bill for the period, without issuing anything.
    @PostMapping("/api/societies/{societyId}/maintenance/cycles/preview")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_CREATE)
    fun preview(@PathVariable societyId: Long, @Valid @RequestBody request: BillingPeriodRequest): BillingPreviewResponse {
        requireSocietyWide(Permissions.MAINTENANCE_BILL_CREATE, societyId)
        val period = request.toPeriod()
        return BillingPreviewResponse.from(period, billingService.preview(societyId, period))
    }

    @PostMapping("/api/societies/{societyId}/maintenance/cycles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.MAINTENANCE_BILL_CREATE)
    fun issue(@PathVariable societyId: Long, @Valid @RequestBody request: BillingPeriodRequest): CycleResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_BILL_CREATE, societyId)
        return cycleResponse(billingService.issue(societyId, request.toPeriod(), access.userId))
    }

    // Dues from before the app: one bill per listed flat, in a one-off OPENING_BALANCE cycle.
    @PostMapping("/api/societies/{societyId}/maintenance/opening-balances")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.MAINTENANCE_BILL_CREATE)
    fun openingBalances(@PathVariable societyId: Long, @Valid @RequestBody request: OpeningBalancesRequest): CycleResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_BILL_CREATE, societyId)
        return cycleResponse(billingService.issueOpeningBalances(societyId, request.toCommand(), access.userId))
    }

    // Newest period first.
    @GetMapping("/api/societies/{societyId}/maintenance/cycles")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_VIEW)
    fun cycles(@PathVariable societyId: Long): List<CycleResponse> {
        requireSocietyWide(Permissions.MAINTENANCE_BILL_VIEW, societyId)
        val cycles = billingService.cycles(societyId)
        val totals = billingService.totalsByCycle(cycles.map { it.id!! })
        return cycles.map { CycleResponse.from(it, totals) }
    }

    @GetMapping("/api/maintenance/cycles/{id}")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_VIEW)
    fun cycle(@PathVariable id: Long): CycleResponse {
        requireSocietyWide(Permissions.MAINTENANCE_BILL_VIEW, billingService.getCycle(id).society.id!!)
        return cycleResponse(id)
    }

    @PostMapping("/api/maintenance/cycles/{id}/cancel")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_UPDATE)
    fun cancel(@PathVariable id: Long, @Valid @RequestBody request: CancelRequest): CycleResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_BILL_UPDATE, billingService.getCycle(id).society.id!!)
        billingService.cancelCycle(id, access.userId, request.reason)
        return cycleResponse(id)
    }

    // The cycle's bills as a CSV download.
    @GetMapping("/api/maintenance/cycles/{id}/export")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_EXPORT)
    fun export(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val cycle = billingService.getCycle(id)
        val societyId = cycle.society.id!!
        requireSocietyWide(Permissions.MAINTENANCE_BILL_EXPORT, societyId)
        val bills = billingService.searchBills(BillSearch(societyId = societyId, cycleId = id, size = ReportRange.MAX_ROWS + 1))
        ReportRange.requireWithinLimit(bills.size)
        val csv = Csv.write(
            listOf(
                "bill_number", "wing", "flat", "period_start", "period_end", "due_date", "total", "amount_paid",
                "outstanding", "status", "status_reason",
            ),
            bills.map {
                listOf(
                    it.billNumber, it.flat.wing.name, it.flat.flatNumber, cycle.periodStart, cycle.periodEnd,
                    cycle.dueDate, it.total, it.amountPaid, it.outstanding, it.status, it.statusReason,
                )
            },
        )
        return Csv.download("maintenance-bills-$societyId-${cycle.periodStart}-to-${cycle.periodEnd}.csv", csv)
    }

    // The society's bills, newest cycle first. Residents get their own flat(s) only; flatId narrows it.
    @GetMapping("/api/maintenance/bills")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_VIEW)
    fun bills(
        @RequestParam societyId: Long,
        @RequestParam(required = false) cycleId: Long?,
        @RequestParam(required = false) flatId: Long?,
        @RequestParam(required = false) status: BillStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<BillResponse> {
        val access = accessService.current()
        access.requireIn(Permissions.MAINTENANCE_BILL_VIEW, societyId)
        val flatIds = if (access.canSocietyWide(Permissions.MAINTENANCE_BILL_VIEW, societyId)) {
            flatId?.let { setOf(it) }
        } else {
            val own = access.flatsWith(Permissions.MAINTENANCE_BILL_VIEW)
            if (flatId != null && flatId !in own) throw AccessDeniedException("You can only see bills of your own flat")
            flatId?.let { setOf(it) } ?: own
        }
        val search = BillSearch(
            societyId = societyId,
            flatIds = flatIds,
            cycleId = cycleId,
            status = status,
            page = page.coerceAtLeast(0),
            size = size.coerceIn(1, 100),
        )
        return billingService.searchBills(search).map { BillResponse.from(it) }
    }

    @GetMapping("/api/maintenance/bills/{id}")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_VIEW)
    fun bill(@PathVariable id: Long): BillResponse = withPayments(viewable(id))

    @PostMapping("/api/maintenance/bills/{id}/waive")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_UPDATE)
    fun waive(@PathVariable id: Long, @Valid @RequestBody request: CancelRequest): BillResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_BILL_UPDATE, billingService.getBill(id).society.id!!)
        billingService.waiveBill(id, access.userId, request.reason)
        return withPayments(billingService.getBill(id))
    }

    private fun viewable(id: Long): MaintenanceBill {
        val bill = billingService.getBill(id)
        if (!accessService.current().canOnFlat(Permissions.MAINTENANCE_BILL_VIEW, bill.society.id!!, bill.flat.id!!)) {
            throw AccessDeniedException("You cannot see this bill")
        }
        return bill
    }

    private fun withPayments(bill: MaintenanceBill) =
        BillResponse.from(bill, withLines = true, payments = paymentService.allocationsOfBill(bill.id!!))

    private fun cycleResponse(id: Long) = CycleResponse.from(billingService.getCycle(id), billingService.totalsByCycle(listOf(id)))

    private fun requireSocietyWide(permission: String, societyId: Long): Access = accessService.current().also {
        if (!it.canSocietyWide(permission, societyId)) {
            throw AccessDeniedException("Missing permission: $permission for this society")
        }
    }
}
