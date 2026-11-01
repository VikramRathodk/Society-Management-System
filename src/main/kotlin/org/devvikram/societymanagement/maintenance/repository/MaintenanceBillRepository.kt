package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.BillStatus
import org.devvikram.societymanagement.maintenance.model.MaintenanceBill
import org.devvikram.societymanagement.user.model.User
import java.math.BigDecimal
import java.time.Instant

// flatIds == null means every flat in the society; an empty set matches nothing.
data class BillSearch(
    val societyId: Long,
    val flatIds: Set<Long>? = null,
    val cycleId: Long? = null,
    val status: BillStatus? = null,
    val page: Int = 0,
    val size: Int = 20,
)

// Bills of one cycle in one status: how many and their total.
data class CycleBillTotals(val cycleId: Long, val status: BillStatus, val count: Long, val total: BigDecimal)

// Reads return bills with cycle, society, flat (and its wing) and statusChangedBy loaded; only findById
// also loads the lines. Status changes are conditional single statements, like complaints.
interface MaintenanceBillRepository {
    // Saves the bills with their lines.
    fun saveAll(bills: List<MaintenanceBill>): List<MaintenanceBill>
    fun findById(id: Long): MaintenanceBill?
    // Newest cycle first, then by bill number.
    fun search(search: BillSearch): List<MaintenanceBill>
    fun totalsByCycle(cycleIds: Collection<Long>): List<CycleBillTotals>
    // Applies only while the bill is in one of `from`; returns whether it did.
    fun changeStatus(id: Long, from: Set<BillStatus>, to: BillStatus, by: User, at: Instant, reason: String): Boolean
    // Every bill of the cycle in one of `from` → CANCELLED; returns how many changed.
    fun cancelByCycle(cycleId: Long, from: Set<BillStatus>, by: User, at: Instant, reason: String): Int
}
