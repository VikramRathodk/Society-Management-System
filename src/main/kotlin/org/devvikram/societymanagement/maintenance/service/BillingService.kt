package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.maintenance.model.BillLineType
import org.devvikram.societymanagement.maintenance.model.BillStatus
import org.devvikram.societymanagement.maintenance.model.CycleKind
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class OpeningBalanceEntry(val flatId: Long, val amount: BigDecimal, val note: String? = null)

// Dues from before the society used the app, as of `asOf`, payable by `dueDate`.
data class OpeningBalancesCommand(val asOf: LocalDate, val dueDate: LocalDate, val entries: List<OpeningBalanceEntry>)

// Billing cycles and bills. *Who* may act is decided by the caller (MaintenanceBillingController). Write
// methods return ids; callers re-read so the response has every association loaded. Every write takes the
// society lock first (see MaintenanceCycleRepository.lockSociety).
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

    // What issuing this period would bill, late charges included, without storing anything. Refused like
    // issue() when the period is invalid or already billed, so the preview never promises a cycle that
    // can't be issued.
    @Transactional(readOnly = true)
    fun preview(societyId: Long, period: BillingPeriod): BillingPlan {
        period.validate()
        requireFree(societyId, period)
        return plan(societyId, period.start)
    }

    // Creates the cycle and one bill per flat in one transaction. The society row stays locked until
    // commit, so a second issue for the same society waits and then sees the first cycle as an overlap.
    @Transactional
    fun issue(societyId: Long, period: BillingPeriod, issuerId: Long): Long {
        period.validate()
        cycleRepository.lockSociety(societyId)
        requireFree(societyId, period)
        val billingPlan = plan(societyId, period.start)
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
        val saved = billRepository.saveAll(
            billingPlan.bills.mapIndexed { index, draft -> newBill(cycle, draft, "%s-%04d".format(prefix, index + 1)) }
        )
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

    // One bill per listed flat for what it owed before the app, in a one-off OPENING_BALANCE cycle. Only one
    // can be issued per society; cancel it to enter the balances again.
    @Transactional
    fun issueOpeningBalances(societyId: Long, command: OpeningBalancesCommand, issuerId: Long): Long {
        require(command.entries.isNotEmpty()) { "Add at least one flat's opening balance" }
        require(!command.dueDate.isBefore(command.asOf)) { "Due date must not be before the 'as of' date" }
        val duplicate = command.entries.groupBy { it.flatId }.filterValues { it.size > 1 }.keys.firstOrNull()
        require(duplicate == null) { "Flat $duplicate is listed more than once" }
        command.entries.forEach {
            require(it.amount.signum() > 0) { "Opening balances must be more than zero" }
            require(it.amount.scale() <= 2) { "Amounts can have at most 2 decimal places" }
        }

        cycleRepository.lockSociety(societyId)
        cycleRepository.findIssuedOpening(societyId)?.let {
            throw ConflictException("Opening balances were already entered (cycle ${it.id}); cancel that cycle first")
        }
        val flats = flatRepository.findBySocietyId(societyId)
        val byFlat = command.entries.associateBy { it.flatId }
        val unknown = byFlat.keys - flats.mapNotNull { it.id }.toSet()
        require(unknown.isEmpty()) { "Flat ${unknown.first()} is not in this society" }

        val society = societyRepository.findById(societyId) ?: throw NoSuchElementException("Society $societyId not found")
        val cycle = cycleRepository.save(
            MaintenanceCycle(society, command.asOf, command.asOf, command.dueDate, user(issuerId), CycleKind.OPENING_BALANCE)
        )
        // Flats come ordered by wing and flat number, so OB numbers follow the building.
        val bills = flats.filter { it.id in byFlat }.mapIndexed { index, flat ->
            val entry = byFlat.getValue(flat.id!!)
            val line = LineDraft(
                headName = OPENING_BALANCE_LINE,
                calcType = BillLineType.OPENING_BALANCE,
                rate = entry.amount.setScale(2),
                quantity = BigDecimal.ONE.setScale(2),
                amount = entry.amount.setScale(2),
                sortOrder = 0,
                note = entry.note?.trim()?.takeIf { it.isNotEmpty() } ?: "Dues up to ${command.asOf}",
            )
            newBill(cycle, BillDraft(flat, listOf(line)), "OB-%04d".format(index + 1))
        }
        billRepository.saveAll(bills)
        return cycle.id!!
    }

    // Cancels the cycle and every bill in it, and frees its period for a new cycle. Refused once any of
    // its bills received money: void those payments first.
    @Transactional
    fun cancelCycle(id: Long, actorId: Long, reason: String) {
        val cleanReason = requireReason(reason)
        val cycle = getCycle(id)
        cycleRepository.lockSociety(cycle.society.id!!)
        val paid = billRepository.countPaidInCycle(id)
        if (paid > 0) {
            throw ConflictException("$paid bill(s) in this cycle have payments; void those payments first")
        }
        val actor = user(actorId)
        val now = Instant.now()
        if (!cycleRepository.cancel(id, actor, now, cleanReason)) {
            throw ConflictException("Cycle is ${getCycle(id).status} and cannot be cancelled")
        }
        billRepository.cancelByCycle(id, CANCELLABLE, actor, now, cleanReason)
    }

    // Writes off what is left of an unpaid or partly paid bill; the flat no longer owes it. Money already
    // paid stays on the bill.
    @Transactional
    fun waiveBill(id: Long, actorId: Long, reason: String) {
        val cleanReason = requireReason(reason)
        val bill = getBill(id)
        cycleRepository.lockSociety(bill.society.id!!)
        if (bill.status !in BillStatus.OPEN ||
            !billRepository.changeStatus(id, BillStatus.OPEN, BillStatus.WAIVED, user(actorId), Instant.now(), cleanReason)
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

    // Charge heads for every flat, plus late charges on the flat's overdue bills as of the period start.
    private fun plan(societyId: Long, asOf: LocalDate): BillingPlan {
        val settings = configService.settingsOf(societyId)
        val heads = configService.chargeHeads(societyId)
        require(heads.any { it.isActive }) { "Add at least one active charge head before billing" }
        val rule = LateFeeRule(settings.lateFeeType, settings.lateFeeValue, settings.graceDays, settings.roundToRupee)
        val open = billRepository.findOpenBySocietyId(societyId)
        val marks = billRepository.lateChargeMarks(open.map { it.id!! }).associate { it.billId to it.chargedThrough }
        val candidates = open.map {
            LateFeeCandidate(it.id!!, it.billNumber, it.flat.id!!, it.cycle.dueDate, it.outstanding, marks[it.id])
        }
        return BillCalculator.plan(
            flatRepository.findBySocietyId(societyId),
            heads,
            settings.roundToRupee,
            LateFees.lines(candidates, rule, asOf),
        )
    }

    private fun newBill(cycle: MaintenanceCycle, draft: BillDraft, number: String): MaintenanceBill =
        MaintenanceBill(cycle, cycle.society, draft.flat, number, draft.total).also { bill ->
            bill.lines += draft.lines.map {
                MaintenanceBillLine(
                    bill, it.headName, it.calcType, it.rate, it.quantity, it.amount, it.sortOrder,
                    it.note, it.sourceBillId, it.chargedThrough,
                )
            }
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
        const val OPENING_BALANCE_LINE = "Opening balance"
        val BILL_NUMBER_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")

        // Bills a cancelled cycle takes with it; cycles with paid bills are refused before this applies.
        val CANCELLABLE = setOf(BillStatus.UNPAID, BillStatus.WAIVED)
    }
}
