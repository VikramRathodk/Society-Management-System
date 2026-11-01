package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.model.BillLineType
import org.devvikram.societymanagement.maintenance.model.LateFeeType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// An open bill that may be charged late fees. chargedThrough: the day an earlier late charge covered up
// to (null if it was never charged).
data class LateFeeCandidate(
    val billId: Long,
    val billNumber: String,
    val flatId: Long,
    val dueDate: LocalDate,
    val outstanding: BigDecimal,
    val chargedThrough: LocalDate?,
)

data class LateFeeRule(val type: LateFeeType, val value: BigDecimal, val graceDays: Int, val roundToRupee: Boolean)

// Late charges for a new cycle starting on `asOf`. Pure, like BillCalculator.
//  - A bill is overdue when asOf is more than graceDays after its due date.
//  - FIXED: `value` rupees, once per bill.
//  - SIMPLE_INTEREST: outstanding × value% a year × days / 365, for the days from the due date (or from where
//    the last charge stopped) to asOf. Grace only decides *whether* to charge, not from when.
object LateFees {
    const val LINE_NAME = "Late charges"
    private val DAYS_IN_YEAR = BigDecimal(365)
    private val HUNDRED = BigDecimal(100)

    fun isOverdue(dueDate: LocalDate, graceDays: Int, asOf: LocalDate): Boolean =
        asOf.isAfter(dueDate.plusDays(graceDays.toLong()))

    // One line per overdue bill, grouped by flat. Zero amounts are left out.
    fun lines(candidates: List<LateFeeCandidate>, rule: LateFeeRule, asOf: LocalDate): Map<Long, List<LineDraft>> {
        if (rule.type == LateFeeType.NONE || rule.value.signum() <= 0) return emptyMap()
        return candidates
            .filter { it.outstanding.signum() > 0 && isOverdue(it.dueDate, rule.graceDays, asOf) }
            .mapNotNull { candidate -> line(candidate, rule, asOf)?.let { candidate.flatId to it } }
            .groupBy({ it.first }, { it.second })
    }

    private fun line(bill: LateFeeCandidate, rule: LateFeeRule, asOf: LocalDate): LineDraft? = when (rule.type) {
        LateFeeType.NONE -> null
        LateFeeType.FIXED -> if (bill.chargedThrough != null) {
            null
        } else {
            LineDraft(
                headName = LINE_NAME,
                calcType = BillLineType.LATE_FEE,
                rate = rule.value.setScale(2),
                quantity = BigDecimal.ONE.setScale(2),
                amount = BillCalculator.round(rule.value, rule.roundToRupee),
                sortOrder = 0,
                note = "Bill ${bill.billNumber}, due ${bill.dueDate}",
                sourceBillId = bill.billId,
                chargedThrough = asOf,
            )
        }
        LateFeeType.SIMPLE_INTEREST -> {
            val from = maxOf(bill.dueDate, bill.chargedThrough ?: bill.dueDate)
            val days = ChronoUnit.DAYS.between(from, asOf)
            if (days <= 0) {
                null
            } else {
                val exact = bill.outstanding * rule.value * BigDecimal(days)
                val amount = BillCalculator.round(exact.divide(HUNDRED * DAYS_IN_YEAR, 10, RoundingMode.HALF_UP), rule.roundToRupee)
                if (amount.signum() == 0) {
                    null
                } else {
                    LineDraft(
                        headName = LINE_NAME,
                        calcType = BillLineType.LATE_FEE,
                        rate = rule.value.setScale(2),
                        quantity = BigDecimal(days).setScale(2),
                        amount = amount,
                        sortOrder = 0,
                        note = "Bill ${bill.billNumber}: ${rule.value.stripTrailingZeros().toPlainString()}% a year " +
                            "on ${bill.outstanding.setScale(2)} for $days days",
                        sourceBillId = bill.billId,
                        chargedThrough = asOf,
                    )
                }
            }
        }
    }
}
