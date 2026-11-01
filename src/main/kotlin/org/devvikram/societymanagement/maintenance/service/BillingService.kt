package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.maintenance.model.BillStatus
import org.devvikram.societymanagement.maintenance.model.MaintenanceBill
import org.devvikram.societymanagement.maintenance.model.MaintenanceBillLine
import org.devvikram.societymanagement.maintenance.model.MaintenanceCycle
import org.devvikram.societymanagement.maintenance.repository.BillSearch
import org.devvikram.societymanagement.maintenance.repository.CycleBillTotals
import org.devvikram.societymanagement.maintenance.repository.MaintenanceBillRepository
import org.devvikram.societymanagement.maintenance.repository.MaintenanceCycleRepository
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.repository.SocietyRepository
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Billing cycles and bills. *Who* may act is decided by the caller (MaintenanceBillingController). Write
// methods return ids; callers re-read so the response has every association loaded.
@Service
class BillingService(
    private val configService: MaintenanceConfigService,
    private val cycleRepository: MaintenanceCycleRepository,
    private val billRepository: MaintenanceBillRepository,
    private val flatRepository: FlatRepository,
    private val societyRepository: SocietyRepository,
    private val userRepository: UserRepository,
    private val events: ApplicationEventPublisher,
) {
    // The period after the society's latest issued cycle, sized by its billing frequency.
    fun suggestNextPeriod(societyId: Long, today: LocalDate): BillingPeriod {
        val settings = configService.settingsOf(societyId)
        val latest = cycleRepository.findLatestIssued(societyId)
        return BillingPeriods.next(latest?.periodEnd, today, settings.billingFrequency, settings.dueDays)
    }

    // What issuing this period would bill, without storing anything. Refused like issue() when the period
    // is invalid or already billed, so the preview never promises a cycle that can't be issued.
    @Transactional(readOnly = true)
    fun preview(societyId: Long, period: BillingPeriod): BillingPlan {
        period.validate()
        requireFree(societyId, period)
        return plan(societyId)
    }

    // Creates the cycle and one bill per flat in one transaction. The society row stays locked until
    // commit, so a second issue for the same society waits and then sees the first cycle as an overlap.
    @Transactional
    fun issue(societyId: Long, period: BillingPeriod, issuerId: Long): Long {
        period.validate()
        cycleRepository.lockSociety(societyId)
        requireFree(societyId, period)
        val billingPlan = plan(societyId)
        require(billingPlan.problems.isEmpty()) {
            "Some flats cannot be billed: " +
                billingPlan.problems.take(PROBLEMS_SHOWN).joinToString("; ") { "${it.flat.wing.name}-${it.flat.flatNumber}: ${it.message}" } +
                if (billingPlan.problems.size > PROBLEMS_SHOWN) " (and ${billingPlan.problems.size - PROBLEMS_SHOWN} more)" else ""
        }
        require(billingPlan.bills.isNotEmpty()) { "The society has no flats to bill" }

        val issuer = user(issuerId)
        val society = societyRepository.findById(societyId) ?: throw NoSuchElementException("Society $societyId not found")
        val cycle = cycleRepository.save(
            MaintenanceCycle(society, period.start, period.end, period.dueDate, issuedBy = issuer)
        )
        val prefix = period.start.format(BILL_NUMBER_MONTH)
        val bills = billingPlan.bills.mapIndexed { index, draft ->
            MaintenanceBill(cycle, society, draft.flat, "%s-%04d".format(prefix, index + 1), draft.total).also { bill ->
                bill.lines += draft.lines.map {
                    MaintenanceBillLine(bill, it.headName, it.calcType, it.rate, it.quantity, it.amount, it.sortOrder)
                }
            }
        }
        val saved = billRepository.saveAll(bills)
        events.publishEvent(
            MaintenanceBillsIssued(
                cycleId = cycle.id!!,
                societyId = societyId,
                periodStart = period.start,
                periodEnd = period.end,
                dueDate = period.dueDate,
                bills = saved.map { IssuedBill(it.id!!, it.flat.id!!, it.flat.flatNumber, it.total) },
            )
        )
        return cycle.id!!
    }

    // Cancels the cycle and every bill in it, and frees its period for a new cycle.
    @Transactional
    fun cancelCycle(id: Long, actorId: Long, reason: String) {
        val cleanReason = requireReason(reason)
        val actor = user(actorId)
        val now = Instant.now()
        if (!cycleRepository.cancel(id, actor, now, cleanReason)) {
            throw ConflictException("Cycle is ${getCycle(id).status} and cannot be cancelled")
        }
        billRepository.cancelByCycle(id, CANCELLABLE, actor, now, cleanReason)
    }

    // Writes off an unpaid bill; the flat no longer owes it.
    @Transactional
    fun waiveBill(id: Long, actorId: Long, reason: String) {
        val cleanReason = requireReason(reason)
        val bill = getBill(id)
        if (bill.status != BillStatus.UNPAID ||
            !billRepository.changeStatus(id, setOf(BillStatus.UNPAID), BillStatus.WAIVED, user(actorId), Instant.now(), cleanReason)
        ) {
            throw ConflictException("Bill is ${getBill(id).status} and cannot be waived")
        }
    }

    fun cycles(societyId: Long): List<MaintenanceCycle> = cycleRepository.findBySocietyId(societyId)

    fun getCycle(id: Long): MaintenanceCycle =
        cycleRepository.findById(id) ?: throw NoSuchElementException("Billing cycle $id not found")

    fun totalsByCycle(cycleIds: Collection<Long>): List<CycleBillTotals> = billRepository.totalsByCycle(cycleIds)

    fun searchBills(search: BillSearch): List<MaintenanceBill> = billRepository.search(search)

    fun getBill(id: Long): MaintenanceBill =
        billRepository.findById(id) ?: throw NoSuchElementException("Bill $id not found")

    private fun plan(societyId: Long): BillingPlan {
        val settings = configService.settingsOf(societyId)
        val heads = configService.chargeHeads(societyId)
        require(heads.any { it.isActive }) { "Add at least one active charge head before billing" }
        return BillCalculator.plan(flatRepository.findBySocietyId(societyId), heads, settings.roundToRupee)
    }

    private fun requireFree(societyId: Long, period: BillingPeriod) {
        val overlapping = cycleRepository.findIssuedOverlapping(societyId, period.start, period.end)
        if (overlapping.isNotEmpty()) {
            val other = overlapping.first()
            throw ConflictException("Period overlaps the cycle ${other.periodStart} to ${other.periodEnd}; cancel it first")
        }
    }

    private fun requireReason(reason: String): String {
        require(reason.isNotBlank()) { "A reason is required" }
        return reason.trim()
    }

    private fun user(id: Long): User = userRepository.findById(id) ?: throw NoSuchElementException("User $id not found")

    private companion object {
        const val PROBLEMS_SHOWN = 5
        val BILL_NUMBER_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")

        // Bills a cancelled cycle takes with it. Once payments exist (M7b), a cycle with paid bills will
        // have to be refused instead.
        val CANCELLABLE = setOf(BillStatus.UNPAID, BillStatus.WAIVED)
    }
}
