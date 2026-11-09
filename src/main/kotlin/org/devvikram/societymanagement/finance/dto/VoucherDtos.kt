package org.devvikram.societymanagement.finance.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.finance.model.AccountType
import org.devvikram.societymanagement.finance.model.DocumentType
import org.devvikram.societymanagement.finance.model.FinanceDocument
import org.devvikram.societymanagement.finance.model.Voucher
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.model.VoucherType
import org.devvikram.societymanagement.finance.service.AmountLine
import org.devvikram.societymanagement.finance.service.BookDefaults
import org.devvikram.societymanagement.finance.service.ContraCommand
import org.devvikram.societymanagement.finance.service.JournalCommand
import org.devvikram.societymanagement.finance.service.JournalLine
import org.devvikram.societymanagement.finance.service.MoneyAccountSummary
import org.devvikram.societymanagement.finance.service.PaymentCommand
import org.devvikram.societymanagement.finance.service.ReceiptCommand
import org.devvikram.societymanagement.finance.service.VoucherContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ---- Requests ----

data class AmountLineRequest(
    @field:NotNull val accountId: Long?,
    @field:NotNull @field:DecimalMin("0.01") @field:Digits(integer = 12, fraction = 2) val amount: BigDecimal?,
) {
    fun toLine() = AmountLine(accountId!!, amount!!)
}

data class ReceiptRequest(
    @field:NotNull val date: LocalDate?,
    @field:NotNull val depositAccountId: Long?,
    @field:NotEmpty @field:Valid val lines: List<AmountLineRequest> = emptyList(),
    @field:Size(max = 200) val payee: String? = null,
    val flatId: Long? = null,
    @field:Size(max = 100) val reference: String? = null,
    @field:Size(max = 500) val narration: String? = null,
) {
    fun toCommand() = ReceiptCommand(date!!, depositAccountId!!, lines.map { it.toLine() }, payee, flatId, reference, narration)
}

data class PaymentRequest(
    @field:NotNull val date: LocalDate?,
    @field:NotNull val payingAccountId: Long?,
    @field:NotEmpty @field:Valid val lines: List<AmountLineRequest> = emptyList(),
    @field:NotBlank @field:Size(max = 200) val payee: String?,
    val flatId: Long? = null,
    @field:Size(max = 100) val reference: String? = null,
    @field:Size(max = 500) val narration: String? = null,
) {
    fun toCommand() = PaymentCommand(date!!, payingAccountId!!, lines.map { it.toLine() }, payee!!, flatId, reference, narration)
}

data class ContraRequest(
    @field:NotNull val date: LocalDate?,
    @field:NotNull val fromAccountId: Long?,
    @field:NotNull val toAccountId: Long?,
    @field:NotNull @field:DecimalMin("0.01") @field:Digits(integer = 12, fraction = 2) val amount: BigDecimal?,
    @field:Size(max = 100) val reference: String? = null,
    @field:Size(max = 500) val narration: String? = null,
) {
    fun toCommand() = ContraCommand(date!!, fromAccountId!!, toAccountId!!, amount!!, reference, narration)
}

// Exactly one of debit and credit per line, above zero.
data class JournalLineRequest(
    @field:NotNull val accountId: Long?,
    @field:Digits(integer = 12, fraction = 2) val debit: BigDecimal? = null,
    @field:Digits(integer = 12, fraction = 2) val credit: BigDecimal? = null,
) {
    fun toLine() = JournalLine(accountId!!, debit ?: BigDecimal.ZERO, credit ?: BigDecimal.ZERO)
}

data class JournalRequest(
    @field:NotNull val date: LocalDate?,
    @field:NotEmpty @field:Valid val lines: List<JournalLineRequest> = emptyList(),
    @field:NotBlank @field:Size(max = 500) val narration: String?,
    @field:Size(max = 100) val reference: String? = null,
) {
    fun toCommand() = JournalCommand(date!!, lines.map { it.toLine() }, narration!!, reference)
}

data class ReasonRequest(@field:Size(max = 500) val reason: String? = null)

data class LockRequest(val lockedUntil: LocalDate? = null, @field:Size(max = 500) val reason: String? = null)

data class BookDefaultsRequest(
    val cashDepositAccountId: Long? = null,
    val chequeDepositAccountId: Long? = null,
    val upiDepositAccountId: Long? = null,
    val transferDepositAccountId: Long? = null,
    val defaultPayingAccountId: Long? = null,
) {
    fun toDefaults() = BookDefaults(cashDepositAccountId, chequeDepositAccountId, upiDepositAccountId, transferDepositAccountId, defaultPayingAccountId)
}

// ---- Responses ----

data class VoucherLineResponse(
    val accountId: Long,
    val accountCode: String,
    val accountName: String,
    val accountType: AccountType,
    val flatId: Long?,
    val flatLabel: String?,
    val debit: BigDecimal,
    val credit: BigDecimal,
)

data class DocumentResponse(
    val id: Long,
    val documentType: DocumentType,
    val filename: String?,
    val contentType: String,
    val sizeBytes: Int,
    val uploadedAt: Instant,
    val uploadedByName: String?,
) {
    companion object {
        fun from(document: FinanceDocument, names: Map<Long, String>) = DocumentResponse(
            document.id!!, document.documentType, document.filename, document.contentType, document.sizeBytes,
            document.uploadedAt, names[document.uploadedBy],
        )
    }
}

data class VoucherResponse(
    val id: Long,
    val societyId: Long,
    val type: VoucherType,
    val number: String,
    val financialYear: String,
    val date: LocalDate,
    val status: VoucherStatus,
    val amount: BigDecimal,
    val payee: String?,
    val reference: String?,
    val narration: String?,
    val flatId: Long?,
    val flatLabel: String?,
    // Set on vouchers posted by maintenance.
    val sourceType: String?,
    val sourceId: Long?,
    // A reversal names the voucher it cancels; a voided voucher names its reversal.
    val reversesVoucherId: Long?,
    val reversedByVoucherId: Long?,
    val reversedByNumber: String?,
    val createdAt: Instant?,
    val createdBy: Long,
    val createdByName: String?,
    val decidedAt: Instant?,
    val decidedByName: String?,
    val voidedAt: Instant?,
    val voidedByName: String?,
    val reason: String?,
    val lines: List<VoucherLineResponse>,
    val documents: List<DocumentResponse>,
) {
    companion object {
        fun from(voucher: Voucher, context: VoucherContext): VoucherResponse {
            val reversal = context.reversals[voucher.id]
            return VoucherResponse(
                id = voucher.id!!,
                societyId = voucher.societyId,
                type = voucher.type,
                number = voucher.number,
                financialYear = voucher.financialYear,
                date = voucher.voucherDate,
                status = voucher.status,
                amount = voucher.amount,
                payee = voucher.payee,
                reference = voucher.reference,
                narration = voucher.narration,
                flatId = voucher.flatId,
                flatLabel = voucher.flatId?.let(context.flats::get),
                sourceType = voucher.sourceType,
                sourceId = voucher.sourceId,
                reversesVoucherId = voucher.reversesVoucherId,
                reversedByVoucherId = reversal?.id,
                reversedByNumber = reversal?.number,
                createdAt = voucher.createdAt,
                createdBy = voucher.createdBy,
                createdByName = context.names[voucher.createdBy],
                decidedAt = voucher.decidedAt,
                decidedByName = voucher.decidedBy?.let(context.names::get),
                voidedAt = voucher.voidedAt,
                voidedByName = voucher.voidedBy?.let(context.names::get),
                reason = voucher.reason,
                lines = voucher.lines.map { line ->
                    val account = context.accounts.getValue(line.accountId)
                    VoucherLineResponse(
                        account.id!!, account.code, account.name, account.type, line.flatId,
                        line.flatId?.let(context.flats::get), line.debit, line.credit,
                    )
                },
                documents = context.documents[voucher.id].orEmpty().map { DocumentResponse.from(it, context.names) },
            )
        }
    }
}

data class MoneyAccountResponse(
    val accountId: Long,
    val code: String,
    val name: String,
    val active: Boolean,
    val opening: BigDecimal,
    val received: BigDecimal,
    val paid: BigDecimal,
    val closing: BigDecimal,
)

data class CashAndBankResponse(
    val from: LocalDate,
    val to: LocalDate,
    val accounts: List<MoneyAccountResponse>,
    val totalOpening: BigDecimal,
    val totalReceived: BigDecimal,
    val totalPaid: BigDecimal,
    val totalClosing: BigDecimal,
) {
    companion object {
        fun from(from: LocalDate, to: LocalDate, rows: List<MoneyAccountSummary>): CashAndBankResponse {
            fun sum(pick: (MoneyAccountSummary) -> BigDecimal) = rows.fold(BigDecimal.ZERO.setScale(2)) { s, r -> s + pick(r) }
            return CashAndBankResponse(
                from, to,
                rows.map { MoneyAccountResponse(it.account.id!!, it.account.code, it.account.name, it.account.active, it.opening, it.received, it.paid, it.closing) },
                sum { it.opening }, sum { it.received }, sum { it.paid }, sum { it.closing },
            )
        }
    }
}

// ---- Maintenance in the books ----

data class ChargeHeadMappingResponse(
    val chargeHeadId: Long,
    val chargeHeadName: String,
    val chargeHeadActive: Boolean,
    val accountId: Long,
    val accountCode: String,
    val accountName: String,
    // false: no ledger chosen, the default (Maintenance charges) applies.
    val mapped: Boolean,
) {
    companion object {
        fun from(mapping: org.devvikram.societymanagement.finance.service.ChargeHeadMapping) = ChargeHeadMappingResponse(
            mapping.head.id!!, mapping.head.name, mapping.head.isActive,
            mapping.account.id!!, mapping.account.code, mapping.account.name, mapping.mapped,
        )
    }
}

// accountId null clears a head's mapping (back to the default).
data class ChargeHeadMappingRequest(@field:NotNull val chargeHeadId: Long?, val accountId: Long? = null)

data class ChargeHeadMappingsRequest(@field:NotEmpty @field:Valid val mappings: List<ChargeHeadMappingRequest> = emptyList())

data class FlatMismatchResponse(
    val flatId: Long,
    val flatLabel: String,
    val booksDues: BigDecimal,
    val maintenanceDues: BigDecimal,
    val booksAdvance: BigDecimal,
    val maintenanceAdvance: BigDecimal,
)

data class MaintenanceCheckResponse(
    val ok: Boolean,
    val booksDues: BigDecimal,
    val maintenanceDues: BigDecimal,
    val booksAdvance: BigDecimal,
    val maintenanceAdvance: BigDecimal,
    val mismatches: List<FlatMismatchResponse>,
) {
    companion object {
        fun from(check: org.devvikram.societymanagement.finance.service.MaintenanceCheck) = MaintenanceCheckResponse(
            check.ok, check.booksDues, check.maintenanceDues, check.booksAdvance, check.maintenanceAdvance,
            check.mismatches.map { FlatMismatchResponse(it.flatId, it.label, it.booksDues, it.maintenanceDues, it.booksAdvance, it.maintenanceAdvance) },
        )
    }
}
