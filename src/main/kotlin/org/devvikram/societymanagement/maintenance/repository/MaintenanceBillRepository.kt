package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.BillStatus
import org.devvikram.societymanagement.maintenance.model.MaintenanceBill
import org.devvikram.societymanagement.user.model.User
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// flatIds == null means every flat in the society; an empty set matches nothing.
data class BillSearch(
    val societyId: Long,
    val flatIds: Set<Long>? = null,
    val cycleId: Long? = null,
    val status: BillStatus? = null,
    val page: Int = 0,
    val size: Int = 20,
)

// Bills of one cycle in one status: how many, their total and how much of it was paid.
data class CycleBillTotals(
    val cycleId: Long,
    val status: BillStatus,
    val count: Long,
    val total: BigDecimal,
    val paid: BigDecimal,
)

// The latest day an overdue bill was charged late fees up to, on bills that were not cancelled.
data class LateChargeMark(val billId: Long, val chargedThrough: LocalDate)

// What one flat owes across its open bills. `overdue` counts bills due before the cutoff the caller gave.
// `credit` is its unused advance (filled in by the report service); a flat with only credit has no open
// bills and no oldest due date.
data class FlatDues @JvmOverloads constructor(
    val flatId: Long,
    val wingName: String,
    val flatNumber: String,
    val openBills: Long,
    val outstanding: BigDecimal,
    val overdue: BigDecimal,
    val oldestDueDate: LocalDate?,
    val credit: BigDecimal = BigDecimal.ZERO.setScale(2),
)

// Reads return bills with cycle, society, flat (and its wing) and statusChangedBy loaded; only findById
// also loads the lines. Waive and cancel are conditional single statements, like complaints; payments
// change amountPaid on loaded bills under the society lock.
interface MaintenanceBillRepository {
    // Saves the bills with their lines.
    fun saveAll(bills: List<MaintenanceBill>): List<MaintenanceBill>
    fun findById(id: Long): MaintenanceBill?
    // Newest cycle first, then by bill number.
    fun search(search: BillSearch): List<MaintenanceBill>
    // UNPAID / PARTIAL bills with something left to pay, oldest due first.
    fun findOpenByFlatId(flatId: Long): List<MaintenanceBill>
    // Same for every flat of the society (cycle and flat loaded, not the wing).
    fun findOpenBySocietyId(societyId: Long): List<MaintenanceBill>
    fun lateChargeMarks(billIds: Collection<Long>): List<LateChargeMark>
    // Bills of the cycle that received any money.
    fun countPaidInCycle(cycleId: Long): Long
    // One row per flat that owes something, by wing and flat number. Bills due before `overdueBefore`
    // count as overdue.
    fun dues(societyId: Long, overdueBefore: LocalDate): List<FlatDues>
    fun totalsByCycle(cycleIds: Collection<Long>): List<CycleBillTotals>
    // Applies only while the bill is in one of `from`; returns whether it did.
    fun changeStatus(id: Long, from: Set<BillStatus>, to: BillStatus, by: User, at: Instant, reason: String): Boolean
    // Every bill of the cycle in one of `from` → CANCELLED; returns how many changed.
    fun cancelByCycle(cycleId: Long, from: Set<BillStatus>, by: User, at: Instant, reason: String): Int
    // Every bill of the cycle with its lines (and flat).
    fun findByCycleWithLines(cycleId: Long): List<MaintenanceBill>
    // Read from the database, not the session: what the bill has been paid right now.
    fun paidAmountOf(billId: Long): java.math.BigDecimal
}
