package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.model.ChargeCalcType
import org.devvikram.societymanagement.maintenance.model.ChargeHead
import org.devvikram.societymanagement.society.model.Flat
import java.math.BigDecimal
import java.math.RoundingMode

data class LineDraft(
    val headName: String,
    val calcType: ChargeCalcType,
    val rate: BigDecimal,
    val quantity: BigDecimal,
    val amount: BigDecimal,
    val sortOrder: Int,
)

data class BillDraft(val flat: Flat, val lines: List<LineDraft>) {
    val total: BigDecimal = lines.fold(BigDecimal.ZERO.setScale(2)) { sum, line -> sum + line.amount }
}

// A flat that can't be billed as configured, e.g. a per-sq-ft charge on a flat with no area.
data class BillingProblem(val flat: Flat, val message: String)

data class BillingPlan(val bills: List<BillDraft>, val problems: List<BillingProblem>) {
    val total: BigDecimal = bills.fold(BigDecimal.ZERO.setScale(2)) { sum, bill -> sum + bill.total }
}

// What each flat owes for one cycle, from the society's active charge heads. Pure: the same inputs
// always give the same plan, so the preview and the issued bills match.
object BillCalculator {
    private val ONE = BigDecimal.ONE.setScale(2)

    fun plan(flats: List<Flat>, heads: List<ChargeHead>, roundToRupee: Boolean): BillingPlan {
        val active = heads.filter { it.isActive }.sortedWith(compareBy({ it.sortOrder }, { it.name }))
        val bills = mutableListOf<BillDraft>()
        val problems = mutableListOf<BillingProblem>()
        for (flat in flats) {
            val missingArea = active.filter { it.calcType == ChargeCalcType.PER_SQFT && flat.areaSqft == null }
            if (missingArea.isNotEmpty()) {
                problems += BillingProblem(flat, "No area set; needed for ${missingArea.joinToString { it.name }}")
                continue
            }
            bills += BillDraft(flat, active.mapIndexed { index, head -> line(head, flat, index, roundToRupee) })
        }
        return BillingPlan(bills, problems)
    }

    private fun line(head: ChargeHead, flat: Flat, index: Int, roundToRupee: Boolean): LineDraft {
        val quantity = when (head.calcType) {
            ChargeCalcType.FIXED -> ONE
            ChargeCalcType.PER_SQFT -> flat.areaSqft!!.setScale(2, RoundingMode.HALF_UP)
        }
        val exact = head.amount * quantity
        val amount = if (roundToRupee) {
            exact.setScale(0, RoundingMode.HALF_UP).setScale(2)
        } else {
            exact.setScale(2, RoundingMode.HALF_UP)
        }
        return LineDraft(head.name, head.calcType, head.amount.setScale(2), quantity, amount, index)
    }
}
