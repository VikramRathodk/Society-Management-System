package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.FinanceAccount
import org.devvikram.societymanagement.finance.model.FinanceDocument
import org.devvikram.societymanagement.finance.model.Voucher
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// What showing vouchers needs beyond the vouchers themselves, loaded once per list.
data class VoucherContext(
    val accounts: Map<Long, FinanceAccount>,
    // "A-101"
    val flats: Map<Long, String>,
    val names: Map<Long, String>,
    val documents: Map<Long, List<FinanceDocument>>,
    // Voucher id -> the voucher reversing it.
    val reversals: Map<Long, Voucher>,
)

@Service
class VoucherContextService(
    private val chartService: ChartService,
    private val voucherService: VoucherService,
    private val documentService: DocumentService,
    private val flatRepository: FlatRepository,
    private val userRepository: UserRepository,
) {
    @Transactional(readOnly = true)
    fun contextFor(societyId: Long, vouchers: List<Voucher>): VoucherContext {
        val ids = vouchers.map { it.id!! }
        val documents = documentService.forVouchers(ids)
        val reversals = vouchers.filter { it.voidedAt != null }.mapNotNull { v -> voucherService.reversalOf(v.id!!)?.let { v.id!! to it } }.toMap()
        val userIds = vouchers.flatMap { listOfNotNull(it.createdBy, it.decidedBy, it.voidedBy) } + documents.map { it.uploadedBy }
        return VoucherContext(
            accounts = chartService.accounts(societyId).associateBy { it.id!! },
            flats = flatRepository.findBySocietyId(societyId).associate { it.id!! to "${it.wing.name}-${it.flatNumber}" },
            names = userRepository.findAllById(userIds.toSet()).associate { it.id!! to it.name },
            documents = documents.groupBy { it.voucherId },
            reversals = reversals,
        )
    }
}
