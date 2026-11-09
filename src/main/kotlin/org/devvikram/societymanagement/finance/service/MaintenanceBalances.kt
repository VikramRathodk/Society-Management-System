package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.maintenance.model.RefundStatus
import org.devvikram.societymanagement.maintenance.service.MaintenanceReportService
import org.devvikram.societymanagement.maintenance.service.RefundService
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

// What each flat owes and holds as credit according to maintenance, in the terms the books use:
//  - dues: the outstanding of its open bills;
//  - advance: its unused credit plus credit held by refunds still waiting for approval (the books only
//    record a refund once it is completed).
data class FlatBalance(val flatId: Long, val label: String, val dues: BigDecimal, val advance: BigDecimal)

@Component
class MaintenanceBalances(
    private val reportService: MaintenanceReportService,
    private val refundService: RefundService,
) {
    fun byFlat(societyId: Long, today: LocalDate): Map<Long, FlatBalance> {
        val pending = refundService.search(societyId, null, setOf(RefundStatus.PENDING_APPROVAL))
            .groupBy({ it.flat.id!! }, { it })
            .mapValues { (_, refunds) -> refunds.fold(BigDecimal.ZERO) { sum, r -> sum + r.amount } }
        val labels = mutableMapOf<Long, String>()
        val rows = reportService.dues(societyId, today).associateBy { it.flatId }
        rows.values.forEach { labels[it.flatId] = "${it.wingName}-${it.flatNumber}" }
        pending.keys.filter { it !in labels }.forEach { flatId ->
            refundService.search(societyId, setOf(flatId), null).firstOrNull()?.let {
                labels[flatId] = "${it.flat.wing.name}-${it.flat.flatNumber}"
            }
        }
        return (rows.keys + pending.keys).associateWith { flatId ->
            val row = rows[flatId]
            FlatBalance(
                flatId = flatId,
                label = labels[flatId] ?: "Flat $flatId",
                dues = (row?.outstanding ?: BigDecimal.ZERO).setScale(2),
                advance = ((row?.credit ?: BigDecimal.ZERO) + (pending[flatId] ?: BigDecimal.ZERO)).setScale(2),
            )
        }
    }
}
