package org.devvikram.societymanagement.maintenance.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.maintenance.model.BillStatus
import org.devvikram.societymanagement.maintenance.model.BillingFrequency
import org.devvikram.societymanagement.maintenance.model.ChargeCalcType
import org.devvikram.societymanagement.maintenance.model.ChargeHead
import org.devvikram.societymanagement.maintenance.model.CycleStatus
import org.devvikram.societymanagement.maintenance.model.MaintenanceBill
import org.devvikram.societymanagement.maintenance.model.MaintenanceBillLine
import org.devvikram.societymanagement.maintenance.model.MaintenanceCycle
import org.devvikram.societymanagement.maintenance.model.MaintenanceSettings
import org.devvikram.societymanagement.maintenance.repository.CycleBillTotals
import org.devvikram.societymanagement.maintenance.service.BillDraft
import org.devvikram.societymanagement.maintenance.service.BillingPeriod
import org.devvikram.societymanagement.maintenance.service.BillingPlan
import org.devvikram.societymanagement.maintenance.service.BillingProblem
import org.devvikram.societymanagement.maintenance.service.LineDraft
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ---- Settings ----

data class UpdateSettingsRequest(
    @field:NotNull val billingFrequency: BillingFrequency?,
    @field:NotNull @field:Min(0) @field:Max(365) val dueDays: Int?,
    @field:NotNull val roundToRupee: Boolean?,
)

data class SettingsResponse(
    val societyId: Long,
    val billingFrequency: BillingFrequency,
    val dueDays: Int,
    val roundToRupee: Boolean,
    // null until the society first saves its settings.
    val updatedAt: Instant?,
    val updatedByName: String?,
) {
    companion object {
        fun from(settings: MaintenanceSettings) = SettingsResponse(
            societyId = settings.societyId,
            billingFrequency = settings.billingFrequency,
            dueDays = settings.dueDays,
            roundToRupee = settings.roundToRupee,
            updatedAt = settings.updatedAt,
            updatedByName = settings.updatedBy?.name,
        )
    }
}

// ---- Charge heads ----

data class AddChargeHeadRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotNull val calcType: ChargeCalcType?,
    @field:NotNull @field:DecimalMin("0") @field:Digits(integer = 10, fraction = 2) val amount: BigDecimal?,
    val active: Boolean = true,
    val sortOrder: Int = 0,
)

// Omitted fields stay as they are.
data class UpdateChargeHeadRequest(
    @field:Size(min = 1, max = 100) val name: String? = null,
    val calcType: ChargeCalcType? = null,
    @field:DecimalMin("0") @field:Digits(integer = 10, fraction = 2) val amount: BigDecimal? = null,
    val active: Boolean? = null,
    val sortOrder: Int? = null,
)

data class ChargeHeadResponse(
    val id: Long,
    val societyId: Long,
    val name: String,
    val calcType: ChargeCalcType,
    val amount: BigDecimal,
    val active: Boolean,
    val sortOrder: Int,
) {
    companion object {
        fun from(head: ChargeHead) = ChargeHeadResponse(
            id = head.id!!,
            societyId = head.society.id!!,
            name = head.name,
            calcType = head.calcType,
            amount = head.amount,
            active = head.isActive,
            sortOrder = head.sortOrder,
        )
    }
}

// ---- Cycles ----

data class BillingPeriodRequest(
    @field:NotNull val periodStart: LocalDate?,
    @field:NotNull val periodEnd: LocalDate?,
    @field:NotNull val dueDate: LocalDate?,
) {
    fun toPeriod() = BillingPeriod(periodStart!!, periodEnd!!, dueDate!!)
}

data class BillingPeriodResponse(val periodStart: LocalDate, val periodEnd: LocalDate, val dueDate: LocalDate) {
    companion object {
        fun from(period: BillingPeriod) = BillingPeriodResponse(period.start, period.end, period.dueDate)
    }
}

data class CancelRequest(@field:NotBlank @field:Size(max = 2000) val reason: String)

data class LineResponse(
    val headName: String,
    val calcType: ChargeCalcType,
    val rate: BigDecimal,
    val quantity: BigDecimal,
    val amount: BigDecimal,
) {
    companion object {
        fun from(line: LineDraft) = LineResponse(line.headName, line.calcType, line.rate, line.quantity, line.amount)
        fun from(line: MaintenanceBillLine) = LineResponse(line.headName, line.calcType, line.rate, line.quantity, line.amount)
    }
}

data class PreviewBillResponse(
    val flatId: Long,
    val wingName: String,
    val flatNumber: String,
    val lines: List<LineResponse>,
    val total: BigDecimal,
) {
    companion object {
        fun from(draft: BillDraft) = PreviewBillResponse(
            flatId = draft.flat.id!!,
            wingName = draft.flat.wing.name,
            flatNumber = draft.flat.flatNumber,
            lines = draft.lines.map(LineResponse::from),
            total = draft.total,
        )
    }
}

data class PreviewProblemResponse(val flatId: Long, val wingName: String, val flatNumber: String, val message: String) {
    companion object {
        fun from(problem: BillingProblem) =
            PreviewProblemResponse(problem.flat.id!!, problem.flat.wing.name, problem.flat.flatNumber, problem.message)
    }
}

// Issuing is refused while `problems` is not empty.
data class BillingPreviewResponse(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val dueDate: LocalDate,
    val billCount: Int,
    val total: BigDecimal,
    val bills: List<PreviewBillResponse>,
    val problems: List<PreviewProblemResponse>,
) {
    companion object {
        fun from(period: BillingPeriod, plan: BillingPlan) = BillingPreviewResponse(
            periodStart = period.start,
            periodEnd = period.end,
            dueDate = period.dueDate,
            billCount = plan.bills.size,
            total = plan.total,
            bills = plan.bills.map(PreviewBillResponse::from),
            problems = plan.problems.map(PreviewProblemResponse::from),
        )
    }
}

data class CycleResponse(
    val id: Long,
    val societyId: Long,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val dueDate: LocalDate,
    val status: CycleStatus,
    val issuedAt: Instant?,
    val issuedById: Long,
    val issuedByName: String,
    val cancelledAt: Instant?,
    val cancelledByName: String?,
    val cancelReason: String?,
    val billCount: Long,
    // Sum of every bill in the cycle, and the number of bills in each status.
    val totalBilled: BigDecimal,
    val billsByStatus: Map<BillStatus, Long>,
) {
    companion object {
        fun from(cycle: MaintenanceCycle, totals: List<CycleBillTotals>): CycleResponse {
            val own = totals.filter { it.cycleId == cycle.id }
            return CycleResponse(
                id = cycle.id!!,
                societyId = cycle.society.id!!,
                periodStart = cycle.periodStart,
                periodEnd = cycle.periodEnd,
                dueDate = cycle.dueDate,
                status = cycle.status,
                issuedAt = cycle.issuedAt,
                issuedById = cycle.issuedBy.id!!,
                issuedByName = cycle.issuedBy.name,
                cancelledAt = cycle.cancelledAt,
                cancelledByName = cycle.cancelledBy?.name,
                cancelReason = cycle.cancelReason,
                billCount = own.sumOf { it.count },
                totalBilled = own.fold(BigDecimal.ZERO.setScale(2)) { sum, it -> sum + it.total },
                billsByStatus = own.associate { it.status to it.count },
            )
        }
    }
}

// ---- Bills ----

data class BillResponse(
    val id: Long,
    val billNumber: String,
    val societyId: Long,
    val cycleId: Long,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val dueDate: LocalDate,
    val flatId: Long,
    val wingName: String,
    val flatNumber: String,
    val total: BigDecimal,
    val status: BillStatus,
    val statusReason: String?,
    val statusChangedAt: Instant?,
    val statusChangedByName: String?,
    val createdAt: Instant?,
    // Only on the single-bill endpoint; null in lists.
    val lines: List<LineResponse>?,
) {
    companion object {
        fun from(bill: MaintenanceBill, withLines: Boolean = false) = BillResponse(
            id = bill.id!!,
            billNumber = bill.billNumber,
            societyId = bill.society.id!!,
            cycleId = bill.cycle.id!!,
            periodStart = bill.cycle.periodStart,
            periodEnd = bill.cycle.periodEnd,
            dueDate = bill.cycle.dueDate,
            flatId = bill.flat.id!!,
            wingName = bill.flat.wing.name,
            flatNumber = bill.flat.flatNumber,
            total = bill.total,
            status = bill.status,
            statusReason = bill.statusReason,
            statusChangedAt = bill.statusChangedAt,
            statusChangedByName = bill.statusChangedBy?.name,
            createdAt = bill.createdAt,
            lines = if (withLines) bill.lines.map(LineResponse::from) else null,
        )
    }
}
