package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.FinanceBooks
import org.devvikram.societymanagement.finance.model.LineEntry
import org.devvikram.societymanagement.finance.model.SystemAccounts
import org.devvikram.societymanagement.maintenance.model.BillLineType
import org.devvikram.societymanagement.maintenance.model.PaymentMethod
import org.devvikram.societymanagement.maintenance.service.IssuedLine
import org.devvikram.societymanagement.maintenance.service.MaintenanceBillWaived
import org.devvikram.societymanagement.maintenance.service.MaintenanceBillsIssued
import org.devvikram.societymanagement.maintenance.service.MaintenanceCreditApplied
import org.devvikram.societymanagement.maintenance.service.MaintenanceCycleCancelled
import org.devvikram.societymanagement.maintenance.service.MaintenancePaymentRecorded
import org.devvikram.societymanagement.maintenance.service.MaintenancePaymentVoided
import org.devvikram.societymanagement.maintenance.service.MaintenanceRefundCompleted
import org.devvikram.societymanagement.maintenance.service.MaintenanceRefundVoided
import org.devvikram.societymanagement.maintenance.service.PaymentService
import org.devvikram.societymanagement.maintenance.service.RefundService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// Maintenance in the books. Each listener runs inside the maintenance transaction (plain
// @EventListener), so a failed posting rolls the maintenance action back, and posts at most one SYSTEM
// voucher per source event (SystemPoster). Nothing is posted until the books are started: the opening
// voucher takes over each flat's dues and credit at that moment.
//
// The member sub-ledgers mirror maintenance: MEMBER_DUES per flat = what its open bills still owe;
// MEMBER_ADVANCE per flat = its unused credit (plus credit held by waiting refunds). The maintenance check
// report compares the two.
@Component
class MaintenancePostings(
    private val poster: SystemPoster,
    private val chartService: ChartService,
    private val mappings: ChargeHeadMappingService,
    private val paymentService: PaymentService,
    private val refundService: RefundService,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {
    private fun today() = LocalDate.now(reportZone)

    private fun account(societyId: Long, key: String): Long = chartService.systemAccount(societyId, key).id!!

    // Bills issued: each flat owes its bill; each line is income of its head's ledger (late charges: late
    // fee income; opening balances: the General fund, as they predate these books).
    @EventListener
    fun onIssued(event: MaintenanceBillsIssued) {
        val source = PostingSource(PostingSource.CYCLE, event.cycleId, "ISSUED")
        if (poster.booksOf(event.societyId) == null || poster.existing(event.societyId, source) != null) return
        val dues = account(event.societyId, SystemAccounts.MEMBER_DUES)
        val credit = creditAccounts(event.societyId, opening = event.opening)
        val lines = event.bills.flatMap { bill ->
            if (bill.total.signum() == 0) emptyList()
            else listOf(LineEntry.debit(dues, bill.total, bill.flatId)) +
                bill.lines.filter { it.amount.signum() > 0 }.map { LineEntry.credit(credit(it), it.amount) }
        }
        val what = if (event.opening) "Maintenance opening balances" else "Maintenance bills ${event.periodStart} to ${event.periodEnd}"
        poster.post(event.societyId, source, event.periodStart, lines, "$what (${event.bills.size} flats)")
    }

    // Credit spent on bills at issue: the advance becomes payment of the dues.
    @EventListener
    fun onCreditApplied(event: MaintenanceCreditApplied) {
        if (poster.booksOf(event.societyId) == null) return
        val advance = account(event.societyId, SystemAccounts.MEMBER_ADVANCE)
        val dues = account(event.societyId, SystemAccounts.MEMBER_DUES)
        val lines = event.flats.flatMap {
            listOf(LineEntry.debit(advance, it.total, it.flatId), LineEntry.credit(dues, it.total, it.flatId))
        }
        poster.post(event.societyId, PostingSource(PostingSource.CYCLE, event.cycleId, "CREDIT_APPLIED"), event.appliedOn,
            lines, "Advance maintenance used for bills from ${event.appliedOn}")
    }

    // Money in: what paid bills reduces the dues, the rest is an advance from the member.
    @EventListener
    fun onPaymentRecorded(event: MaintenancePaymentRecorded) {
        val payment = paymentService.get(event.paymentId)
        val explicit = payment.depositAccountId?.let { requireMoney(event.societyId, it) }
        val books = poster.booksOf(event.societyId) ?: return
        val into = explicit ?: depositDefault(books, payment.method)
        val allocated = payment.allocations.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }
        val excess = payment.amount - allocated
        val flatId = payment.flat.id!!
        val lines = listOfNotNull(
            debit(into, payment.amount),
            credit(account(event.societyId, SystemAccounts.MEMBER_DUES), allocated, flatId),
            credit(account(event.societyId, SystemAccounts.MEMBER_ADVANCE), excess, flatId),
        )
        poster.post(event.societyId, PostingSource(PostingSource.PAYMENT, payment.id!!, "RECORDED"), payment.paidOn, lines,
            "Maintenance receipt ${payment.receiptNumber}, ${payment.flat.wing.name}-${payment.flat.flatNumber}",
            flatId = flatId, reference = payment.reference)
    }

    // Money that didn't come after all. Worked out from the payment as it stands, so it is right whether or
    // not it was recorded before the books started: its bills (paid directly or from its credit) are owed
    // again, its remaining credit is gone, and the money leaves the account it went into.
    @EventListener
    fun onPaymentVoided(event: MaintenancePaymentVoided) {
        val books = poster.booksOf(event.societyId) ?: return
        val payment = paymentService.get(event.paymentId)
        val recorded = poster.existing(event.societyId, PostingSource(PostingSource.PAYMENT, payment.id!!, "RECORDED"))
        val from = recorded?.lines?.firstOrNull { it.debit.signum() > 0 && it.flatId == null }?.accountId
            ?: payment.depositAccountId ?: depositDefault(books, payment.method)
        val onBills = payment.allocations.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }
        val flatId = payment.flat.id!!
        val lines = listOfNotNull(
            credit(from, payment.amount),
            debit(account(event.societyId, SystemAccounts.MEMBER_DUES), onBills, flatId),
            debit(account(event.societyId, SystemAccounts.MEMBER_ADVANCE), payment.amount - onBills, flatId),
        )
        poster.post(event.societyId, PostingSource(PostingSource.PAYMENT, payment.id!!, "VOIDED"), today(), lines,
            "Void of maintenance receipt ${payment.receiptNumber}: ${payment.voidReason ?: ""}".trimEnd(':', ' '),
            flatId = flatId)
    }

    @EventListener
    fun onWaived(event: MaintenanceBillWaived) {
        if (event.writtenOff.signum() <= 0 || poster.booksOf(event.societyId) == null) return
        poster.post(event.societyId, PostingSource(PostingSource.BILL, event.billId, "WAIVED"), today(),
            listOf(
                LineEntry.debit(account(event.societyId, SystemAccounts.WRITE_OFF), event.writtenOff),
                LineEntry.credit(account(event.societyId, SystemAccounts.MEMBER_DUES), event.writtenOff, event.flatId),
            ),
            "Maintenance bill waived", flatId = event.flatId)
    }

    // Undoes the cancelled bills: they are no longer owed or earned (against the General fund when they
    // were billed before the books started), waivers on them are undone, and credit they used goes back
    // to the flats' advances.
    @EventListener
    fun onCycleCancelled(event: MaintenanceCycleCancelled) {
        if (poster.booksOf(event.societyId) == null) return
        val societyId = event.societyId
        val dues = account(societyId, SystemAccounts.MEMBER_DUES)
        val advance = account(societyId, SystemAccounts.MEMBER_ADVANCE)
        val generalFund = account(societyId, SystemAccounts.GENERAL_FUND)
        val writeOff = account(societyId, SystemAccounts.WRITE_OFF)
        val billedInBooks = poster.existing(societyId, PostingSource(PostingSource.CYCLE, event.cycleId, "ISSUED")) != null
        val credit = creditAccounts(societyId, opening = event.opening)
        val lines = mutableListOf<LineEntry>()
        for (bill in event.bills) {
            if (bill.total.signum() > 0) {
                lines += LineEntry.credit(dues, bill.total, bill.flatId)
                bill.lines.filter { it.amount.signum() > 0 }.forEach {
                    lines += LineEntry.debit(if (billedInBooks) credit(it) else generalFund, it.amount)
                }
            }
            if (bill.writtenOff.signum() > 0) {
                val waivedInBooks = poster.existing(societyId, PostingSource(PostingSource.BILL, bill.billId, "WAIVED")) != null
                lines += LineEntry.debit(dues, bill.writtenOff, bill.flatId)
                lines += LineEntry.credit(if (waivedInBooks) writeOff else generalFund, bill.writtenOff)
            }
        }
        for (released in event.releasedCredit) {
            lines += LineEntry.debit(dues, released.amount, released.flatId)
            lines += LineEntry.credit(advance, released.amount, released.flatId)
        }
        poster.post(societyId, PostingSource(PostingSource.CYCLE, event.cycleId, "CANCELLED"), today(), lines,
            "Maintenance cycle cancelled")
    }

    // Credit paid back: the advance goes down and the money leaves the account it was paid from.
    @EventListener
    fun onRefundCompleted(event: MaintenanceRefundCompleted) {
        val refund = refundService.get(event.refundId)
        val explicit = refund.paidFromAccountId?.let { requireMoney(event.societyId, it) }
        val books = poster.booksOf(event.societyId) ?: return
        val from = explicit ?: books.defaultPayingAccountId ?: books.cashDepositAccountId ?: account(event.societyId, SystemAccounts.CASH)
        val flatId = refund.flat.id!!
        poster.post(event.societyId, PostingSource(PostingSource.REFUND, refund.id!!, "COMPLETED"), refund.paidOn,
            listOf(
                LineEntry.debit(account(event.societyId, SystemAccounts.MEMBER_ADVANCE), refund.amount, flatId),
                LineEntry.credit(from, refund.amount),
            ),
            "Maintenance refund ${refund.refundNumber}, ${refund.flat.wing.name}-${refund.flat.flatNumber}",
            flatId = flatId, reference = refund.reference)
    }

    @EventListener
    fun onRefundVoided(event: MaintenanceRefundVoided) {
        val books = poster.booksOf(event.societyId) ?: return
        val refund = refundService.get(event.refundId)
        val completed = poster.existing(event.societyId, PostingSource(PostingSource.REFUND, refund.id!!, "COMPLETED"))
        val into = completed?.lines?.firstOrNull { it.credit.signum() > 0 && it.flatId == null }?.accountId
            ?: refund.paidFromAccountId ?: books.defaultPayingAccountId ?: books.cashDepositAccountId
            ?: account(event.societyId, SystemAccounts.CASH)
        val flatId = refund.flat.id!!
        poster.post(event.societyId, PostingSource(PostingSource.REFUND, refund.id!!, "VOIDED"), today(),
            listOf(
                LineEntry.debit(into, refund.amount),
                LineEntry.credit(account(event.societyId, SystemAccounts.MEMBER_ADVANCE), refund.amount, flatId),
            ),
            "Void of maintenance refund ${refund.refundNumber}", flatId = flatId)
    }

    // ---- helpers ----

    // A line, or nothing for a zero amount (LineEntry itself refuses zero).
    private fun debit(accountId: Long, amount: BigDecimal, flatId: Long? = null) =
        if (amount.signum() > 0) LineEntry.debit(accountId, amount, flatId) else null

    private fun credit(accountId: Long, amount: BigDecimal, flatId: Long? = null) =
        if (amount.signum() > 0) LineEntry.credit(accountId, amount, flatId) else null

    // The ledger a bill line credits.
    private fun creditAccounts(societyId: Long, opening: Boolean): (IssuedLine) -> Long {
        val generalFund = account(societyId, SystemAccounts.GENERAL_FUND)
        if (opening) return { generalFund }
        val byHead = mappings.accountsByHeadName(societyId)
        val lateFees = account(societyId, SystemAccounts.LATE_FEE_INCOME)
        val default = account(societyId, SystemAccounts.MAINTENANCE_INCOME)
        return { line ->
            when (line.type) {
                BillLineType.LATE_FEE -> lateFees
                BillLineType.OPENING_BALANCE -> generalFund
                else -> byHead[line.headName.lowercase()] ?: default
            }
        }
    }

    // The account a payment by this method goes into when it doesn't name one.
    private fun depositDefault(books: FinanceBooks, method: PaymentMethod): Long {
        val byMethod = when (method) {
            PaymentMethod.CASH -> books.cashDepositAccountId
            PaymentMethod.CHEQUE -> books.chequeDepositAccountId
            PaymentMethod.UPI -> books.upiDepositAccountId
            PaymentMethod.BANK_TRANSFER -> books.transferDepositAccountId
        }
        return byMethod ?: books.cashDepositAccountId ?: account(books.societyId, SystemAccounts.CASH)
    }

    // An account a maintenance payment or refund names: an active cash/bank ledger of the society.
    // Checked even before the books start, so a wrong id is refused (400) rather than kept.
    private fun requireMoney(societyId: Long, accountId: Long): Long {
        val account = chartService.requirePostable(societyId, accountId)
        require(account.id in chartService.moneyAccountIds(societyId)) { "${account.name} is not a cash or bank account" }
        return account.id!!
    }
}
