package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.finance.model.FinanceBooks
import org.devvikram.societymanagement.finance.model.LineEntry
import org.devvikram.societymanagement.finance.model.Voucher
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.model.VoucherType
import org.devvikram.societymanagement.finance.repository.BooksRepository
import org.devvikram.societymanagement.finance.repository.VoucherRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// What caused an automatic posting. Each source event happens at most once, so the triple is unique
// (also in the database).
data class PostingSource(val type: String, val id: Long, val event: String) {
    companion object {
        const val CYCLE = "MAINTENANCE_CYCLE"
        const val PAYMENT = "MAINTENANCE_PAYMENT"
        const val BILL = "MAINTENANCE_BILL"
        const val REFUND = "MAINTENANCE_REFUND"
    }
}

// Writes SYSTEM vouchers for other modules' events. Rules:
//  - nothing is posted while the society's books aren't started (the openings capture that state);
//  - a source event already posted returns the existing voucher (idempotent);
//  - lines on the same account and flat are netted, zero ones dropped; nothing left posts nothing;
//  - the date is the event's own day, but never after today, before the books start or inside the locked
//    period (then the first open day, noted in the narration), so maintenance is never blocked;
//  - accounts deactivated since are still posted to.
// Callers run inside the maintenance transaction, which already holds the society lock.
@Component
class SystemPoster(
    private val booksRepository: BooksRepository,
    private val voucherRepository: VoucherRepository,
    private val voucherWriter: VoucherWriter,
    private val accessService: AccessService,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {
    fun booksOf(societyId: Long): FinanceBooks? = booksRepository.findBySocietyId(societyId)

    fun existing(societyId: Long, source: PostingSource): Voucher? =
        voucherRepository.findBySource(societyId, source.type, source.id, source.event)

    @Transactional(propagation = Propagation.MANDATORY)
    fun post(
        societyId: Long,
        source: PostingSource,
        day: LocalDate,
        lines: List<LineEntry>,
        narration: String,
        flatId: Long? = null,
        reference: String? = null,
    ): Voucher? {
        val books = booksOf(societyId) ?: return null
        existing(societyId, source)?.let { return it }
        val netted = net(lines)
        if (netted.isEmpty()) return null
        val today = LocalDate.now(reportZone)
        val open = listOfNotNull(books.startDate, books.lockedUntil?.plusDays(1)).max()
        val date = maxOf(minOf(day, today), open)
        val note = if (date != day && day < open) " (booked on $date: the books are closed for $day)" else ""
        val voucher = voucherWriter.write(
            societyId,
            VoucherDraft(
                type = VoucherType.SYSTEM,
                date = date,
                status = VoucherStatus.POSTED,
                lines = netted,
                narration = (narration + note).take(500),
                reference = reference?.take(100),
                flatId = flatId,
                allowInactive = true,
            ),
            accessService.currentOrNull()?.userId ?: books.startedBy,
        )
        voucher.sourceType = source.type
        voucher.sourceId = source.id
        voucher.sourceEvent = source.event
        return voucherRepository.save(voucher)
    }

    // One line per account and flat, on whichever side the net amount falls.
    private fun net(lines: List<LineEntry>): List<LineEntry> =
        lines.groupBy { it.accountId to it.flatId }.mapNotNull { (key, group) ->
            val net = group.fold(BigDecimal.ZERO) { sum, line -> sum + line.debit - line.credit }
            when (net.signum()) {
                1 -> LineEntry.debit(key.first, net, key.second)
                -1 -> LineEntry.credit(key.first, net.negate(), key.second)
                else -> null
            }
        }
}
