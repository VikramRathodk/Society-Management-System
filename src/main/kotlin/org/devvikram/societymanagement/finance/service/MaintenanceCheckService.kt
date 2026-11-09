package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.SystemAccounts
import org.devvikram.societymanagement.finance.repository.VoucherRepository
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

// One flat whose books disagree with maintenance.
data class FlatMismatch(
    val flatId: Long,
    val label: String,
    val booksDues: BigDecimal,
    val maintenanceDues: BigDecimal,
    val booksAdvance: BigDecimal,
    val maintenanceAdvance: BigDecimal,
)

data class MaintenanceCheck(
    val booksDues: BigDecimal,
    val maintenanceDues: BigDecimal,
    val booksAdvance: BigDecimal,
    val maintenanceAdvance: BigDecimal,
    // Empty when everything agrees, which it always should.
    val mismatches: List<FlatMismatch>,
) {
    val ok: Boolean get() = mismatches.isEmpty()
}

// Compares, flat by flat, the member sub-ledgers (all entries, whatever their date) with what maintenance
// says each flat owes and holds as credit. Any difference means a posting went missing or wrong: it is the
// guard against bugs in the automatic postings.
@Service
class MaintenanceCheckService(
    private val voucherRepository: VoucherRepository,
    private val chartService: ChartService,
    private val booksService: BooksService,
    private val balances: MaintenanceBalances,
    private val flatRepository: FlatRepository,
) {
    @Transactional(readOnly = true)
    fun check(societyId: Long, today: LocalDate): MaintenanceCheck {
        booksService.requireBooks(societyId)
        val duesAccount = chartService.systemAccount(societyId, SystemAccounts.MEMBER_DUES)
        val advanceAccount = chartService.systemAccount(societyId, SystemAccounts.MEMBER_ADVANCE)
        val booksDues = voucherRepository.totalsByFlat(duesAccount.id!!).mapValues { (_, t) -> duesAccount.type.natural(t.debit, t.credit) }
        val booksAdvance = voucherRepository.totalsByFlat(advanceAccount.id!!).mapValues { (_, t) -> advanceAccount.type.natural(t.debit, t.credit) }
        val maintenance = balances.byFlat(societyId, today)
        val labels = flatRepository.findBySocietyId(societyId).associate { it.id!! to "${it.wing.name}-${it.flatNumber}" }
        val zero = BigDecimal.ZERO.setScale(2)

        val mismatches = (booksDues.keys + booksAdvance.keys + maintenance.keys).sorted().mapNotNull { flatId ->
            val row = FlatMismatch(
                flatId = flatId,
                label = labels[flatId] ?: "Flat $flatId",
                booksDues = booksDues[flatId]?.setScale(2) ?: zero,
                maintenanceDues = maintenance[flatId]?.dues ?: zero,
                booksAdvance = booksAdvance[flatId]?.setScale(2) ?: zero,
                maintenanceAdvance = maintenance[flatId]?.advance ?: zero,
            )
            row.takeIf { it.booksDues.compareTo(it.maintenanceDues) != 0 || it.booksAdvance.compareTo(it.maintenanceAdvance) != 0 }
        }
        fun sum(values: Collection<BigDecimal>) = values.fold(zero) { s, v -> s + v }.setScale(2)
        return MaintenanceCheck(
            booksDues = sum(booksDues.values),
            maintenanceDues = sum(maintenance.values.map { it.dues }),
            booksAdvance = sum(booksAdvance.values),
            maintenanceAdvance = sum(maintenance.values.map { it.advance }),
            mismatches = mismatches,
        )
    }
}
