package org.devvikram.societymanagement.finance.dto

import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.finance.model.AmountStyle
import org.devvikram.societymanagement.finance.model.BankLine
import org.devvikram.societymanagement.finance.model.BankLineState
import org.devvikram.societymanagement.finance.model.StatementImport
import org.devvikram.societymanagement.finance.model.StatementMapping
import org.devvikram.societymanagement.finance.model.StatementStatus
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.model.VoucherType
import org.devvikram.societymanagement.finance.repository.BookEntryRow
import org.devvikram.societymanagement.finance.service.AutoMatchResult
import org.devvikram.societymanagement.finance.service.Brs
import org.devvikram.societymanagement.finance.service.BankLineView
import org.devvikram.societymanagement.finance.service.BookEntryView
import org.devvikram.societymanagement.finance.service.LineVoucherCommand
import org.devvikram.societymanagement.finance.service.Suggestions
import org.devvikram.societymanagement.finance.service.CreateStatementCommand
import org.devvikram.societymanagement.finance.service.ImportResult
import org.devvikram.societymanagement.finance.service.MappingSpec
import org.devvikram.societymanagement.finance.service.ParsedLine
import org.devvikram.societymanagement.finance.service.ParsedStatement
import org.devvikram.societymanagement.finance.service.PreviewResult
import org.devvikram.societymanagement.finance.service.RowNote
import org.devvikram.societymanagement.finance.service.StatementCsv
import org.devvikram.societymanagement.finance.service.StatementView
import org.devvikram.societymanagement.finance.service.UpdateStatementCommand
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ---- Column mapping ----

// Columns are 1-based. `delimiter` is one of "," ";" "\t" "|".
data class StatementMappingRequest(
    @field:NotBlank @field:Size(min = 1, max = 1) val delimiter: String? = ",",
    @field:NotNull @field:Min(1) @field:Max(100) val headerRow: Int?,
    @field:NotNull @field:Min(1) @field:Max(100) val dateColumn: Int?,
    @field:NotBlank val dateFormat: String?,
    @field:NotNull @field:Min(1) @field:Max(100) val descriptionColumn: Int?,
    @field:Min(1) @field:Max(100) val referenceColumn: Int? = null,
    @field:NotNull val amountStyle: AmountStyle?,
    @field:Min(1) @field:Max(100) val debitColumn: Int? = null,
    @field:Min(1) @field:Max(100) val creditColumn: Int? = null,
    @field:Min(1) @field:Max(100) val amountColumn: Int? = null,
    @field:Min(1) @field:Max(100) val typeColumn: Int? = null,
    @field:Min(1) @field:Max(100) val balanceColumn: Int? = null,
) {
    fun toSpec() = MappingSpec(
        delimiter = delimiter!!.single(),
        headerRow = headerRow!!,
        dateColumn = dateColumn!!,
        dateFormat = dateFormat!!,
        descriptionColumn = descriptionColumn!!,
        referenceColumn = referenceColumn,
        amountStyle = amountStyle!!,
        debitColumn = debitColumn,
        creditColumn = creditColumn,
        amountColumn = amountColumn,
        typeColumn = typeColumn,
        balanceColumn = balanceColumn,
    )
}

data class MappingResponse(
    val delimiter: String,
    val headerRow: Int,
    val dateColumn: Int,
    val dateFormat: String,
    val descriptionColumn: Int,
    val referenceColumn: Int?,
    val amountStyle: AmountStyle,
    val debitColumn: Int?,
    val creditColumn: Int?,
    val amountColumn: Int?,
    val typeColumn: Int?,
    val balanceColumn: Int?,
) {
    companion object {
        fun from(spec: MappingSpec) = MappingResponse(
            spec.delimiter.toString(), spec.headerRow, spec.dateColumn, spec.dateFormat, spec.descriptionColumn,
            spec.referenceColumn, spec.amountStyle, spec.debitColumn, spec.creditColumn, spec.amountColumn,
            spec.typeColumn, spec.balanceColumn,
        )
    }
}

// The account's saved mapping (null before the first one) and the date formats a mapping may use.
data class StatementMappingResponse(
    val accountId: Long,
    val mapping: MappingResponse?,
    val updatedAt: Instant?,
    val dateFormats: List<String>,
) {
    companion object {
        fun of(accountId: Long, mapping: StatementMapping?, spec: MappingSpec?) =
            StatementMappingResponse(accountId, spec?.let(MappingResponse::from), mapping?.updatedAt, StatementCsv.DATE_FORMATS)
    }
}

data class RowNoteResponse(val row: Int, val message: String) {
    companion object {
        fun from(note: RowNote) = RowNoteResponse(note.row, note.message)
    }
}

data class ParsedLineResponse(
    val row: Int,
    val date: LocalDate,
    val description: String,
    val reference: String?,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val balance: BigDecimal?,
) {
    companion object {
        fun from(line: ParsedLine) = ParsedLineResponse(line.row, line.date, line.description, line.reference, line.debit, line.credit, line.balance)
    }
}

data class ParsedStatementResponse(
    val lineCount: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val credits: BigDecimal,
    val debits: BigDecimal,
    // At most 50 of each list.
    val lines: List<ParsedLineResponse>,
    val ignored: List<RowNoteResponse>,
    val errors: List<RowNoteResponse>,
    val errorCount: Int,
    val warnings: List<RowNoteResponse>,
) {
    companion object {
        private const val LIMIT = 50

        fun from(parsed: ParsedStatement) = ParsedStatementResponse(
            lineCount = parsed.lines.size,
            firstDate = parsed.lines.firstOrNull()?.date,
            lastDate = parsed.lines.lastOrNull()?.date,
            credits = parsed.lines.fold(BigDecimal.ZERO.setScale(2)) { sum, l -> sum + l.credit },
            debits = parsed.lines.fold(BigDecimal.ZERO.setScale(2)) { sum, l -> sum + l.debit },
            lines = parsed.lines.take(LIMIT).map(ParsedLineResponse::from),
            ignored = parsed.ignored.take(LIMIT).map(RowNoteResponse::from),
            errors = parsed.errors.take(LIMIT).map(RowNoteResponse::from),
            errorCount = parsed.errors.size,
            warnings = parsed.warnings.take(LIMIT).map(RowNoteResponse::from),
        )
    }
}

data class StatementPreviewResponse(
    val delimiter: String,
    val rows: List<List<String>>,
    val totalRows: Int,
    val mappingSource: String,
    val mapping: MappingResponse?,
    val parsed: ParsedStatementResponse?,
) {
    companion object {
        fun from(preview: PreviewResult) = StatementPreviewResponse(
            delimiter = preview.delimiter.toString(),
            rows = preview.rows,
            totalRows = preview.totalRows,
            mappingSource = preview.mappingSource,
            mapping = preview.mapping?.let(MappingResponse::from),
            parsed = preview.parsed?.let(ParsedStatementResponse::from),
        )
    }
}

// ---- Statements ----

// Balances as the bank shows them: positive is money in the account, negative is overdrawn.
data class CreateStatementRequest(
    @field:NotNull val periodFrom: LocalDate?,
    @field:NotNull val periodTo: LocalDate?,
    @field:NotNull @field:Digits(integer = 12, fraction = 2) val openingBalance: BigDecimal?,
    @field:NotNull @field:Digits(integer = 12, fraction = 2) val closingBalance: BigDecimal?,
) {
    fun toCommand() = CreateStatementCommand(periodFrom!!, periodTo!!, openingBalance!!, closingBalance!!)
}

data class UpdateStatementRequest(
    val periodFrom: LocalDate? = null,
    val periodTo: LocalDate? = null,
    @field:Digits(integer = 12, fraction = 2) val openingBalance: BigDecimal? = null,
    @field:Digits(integer = 12, fraction = 2) val closingBalance: BigDecimal? = null,
) {
    fun toCommand() = UpdateStatementCommand(periodFrom, periodTo, openingBalance, closingBalance)
}

data class StatementResponse(
    val id: Long,
    val accountId: Long,
    val accountName: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val status: StatementStatus,
    val lines: Int,
    val matched: Int,
    val unmatched: Int,
    val excluded: Int,
    val imports: Int,
    val credits: BigDecimal,
    val debits: BigDecimal,
    // Opening + credits − debits of the imported lines, and how far that is from the closing balance.
    val linesClosing: BigDecimal,
    val linesDifference: BigDecimal,
    val createdAt: Instant,
    val completedAt: Instant?,
    val reopenedAt: Instant?,
    val reopenReason: String?,
) {
    companion object {
        fun from(view: StatementView) = with(view.statement) {
            StatementResponse(
                id = id!!,
                accountId = accountId,
                accountName = view.accountName,
                periodFrom = periodFrom,
                periodTo = periodTo,
                openingBalance = openingBalance,
                closingBalance = closingBalance,
                status = status,
                lines = view.lines,
                matched = view.matched,
                unmatched = view.unmatched,
                excluded = view.excluded,
                imports = view.imports,
                credits = view.credits,
                debits = view.debits,
                linesClosing = view.linesClosing,
                linesDifference = view.linesDifference,
                createdAt = createdAt,
                completedAt = completedAt,
                reopenedAt = reopenedAt,
                reopenReason = reopenReason,
            )
        }
    }
}

data class StatementImportResponse(
    val id: Long,
    val statementId: Long,
    val filename: String?,
    val sizeBytes: Int,
    val rowsImported: Int,
    val rowsDuplicate: Int,
    val rowsIgnored: Int,
    val importedAt: Instant,
    val importedBy: Long,
) {
    companion object {
        fun from(i: StatementImport) = StatementImportResponse(
            i.id!!, i.statementId, i.filename, i.sizeBytes, i.rowsImported, i.rowsDuplicate, i.rowsIgnored, i.importedAt, i.importedBy,
        )
    }
}

data class ImportResultResponse(
    val import: StatementImportResponse,
    val statement: StatementResponse,
    // At most 50 of each.
    val outsidePeriod: List<RowNoteResponse>,
    val ignored: List<RowNoteResponse>,
    val warnings: List<RowNoteResponse>,
    // Cleared straight after the import (see auto-match).
    val autoMatched: Int,
    val autoPaired: Int,
) {
    companion object {
        fun from(result: ImportResult, auto: AutoMatchResult, statement: StatementView) = ImportResultResponse(
            import = StatementImportResponse.from(result.statementImport),
            statement = StatementResponse.from(statement),
            outsidePeriod = result.outsidePeriod.take(50).map(RowNoteResponse::from),
            ignored = result.ignored.take(50).map(RowNoteResponse::from),
            warnings = result.warnings.take(50).map(RowNoteResponse::from),
            autoMatched = auto.matched,
            autoPaired = auto.paired,
        )
    }
}

// ---- Matching ----

// An entry of the bank ledger in the books: money into the bank (a receipt) or out of it (a payment).
data class BookEntryResponse(
    val lineId: Long,
    val voucherId: Long,
    val number: String,
    val type: VoucherType,
    val date: LocalDate,
    val status: VoucherStatus,
    // A reversal of a voided entry.
    val reversal: Boolean,
    val narration: String?,
    val payee: String?,
    val reference: String?,
    val moneyIn: BigDecimal,
    val moneyOut: BigDecimal,
    // Where it cleared on this statement (cleared lists only): a bank line, or the entry it cancels.
    val bankLineId: Long? = null,
    val pairLineId: Long? = null,
    val auto: Boolean? = null,
) {
    companion object {
        fun from(row: BookEntryRow) = BookEntryResponse(
            row.lineId, row.voucherId, row.number, row.type, row.date, row.status, row.reversesVoucherId != null,
            row.narration, row.payee, row.reference, row.debit, row.credit,
        )

        fun from(view: BookEntryView) = from(view.row).copy(bankLineId = view.bankLineId, pairLineId = view.pairLineId, auto = view.auto)
    }
}

// A bank line with the entries it cleared and the voucher recorded from it.
data class BankLineDetailResponse(
    val id: Long,
    val lineNo: Int,
    val date: LocalDate,
    val description: String,
    val reference: String?,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val balance: BigDecimal?,
    val state: BankLineState,
    val excludeReason: String?,
    val importId: Long,
    val entries: List<BookEntryResponse>,
    // Matched automatically (null while unmatched).
    val auto: Boolean?,
    val voucherId: Long?,
    val voucherNumber: String?,
    val voucherStatus: VoucherStatus?,
) {
    companion object {
        fun from(view: BankLineView) = with(view.line) {
            BankLineDetailResponse(
                id!!, lineNo, txnDate, description, reference, debit, credit, balance, state, excludeReason, importId,
                view.entries.map(BookEntryResponse::from), view.auto, voucherId, view.voucher?.number, view.voucher?.status,
            )
        }
    }
}

data class SuggestionResponse(val entry: BookEntryResponse, val daysApart: Long, val withinWindow: Boolean)

data class SuggestionsResponse(val single: List<SuggestionResponse>, val groups: List<List<BookEntryResponse>>) {
    companion object {
        fun from(s: Suggestions) = SuggestionsResponse(
            s.single.map { SuggestionResponse(BookEntryResponse.from(it.entry), it.days, it.withinWindow) },
            s.groups.map { group -> group.map(BookEntryResponse::from) },
        )
    }
}

data class MatchRequest(@field:NotEmpty @field:Size(max = 200) val bookLineIds: List<Long>?)

data class PairRequest(@field:NotNull val firstLineId: Long?, @field:NotNull val secondLineId: Long?)

data class ExcludeLineRequest(@field:NotBlank @field:Size(max = 500) val reason: String?)

// The other side of the voucher (an income or expense account, or another cash/bank account for a
// transfer). Payee, narration and reference default to the bank line's.
data class LineVoucherRequest(
    @field:NotNull val accountId: Long?,
    @field:Size(max = 200) val payee: String? = null,
    @field:Size(max = 500) val narration: String? = null,
    @field:Size(max = 100) val reference: String? = null,
) {
    fun toCommand() = LineVoucherCommand(accountId!!, payee, narration, reference)
}

data class LineVoucherResponse(
    val voucherId: Long,
    val voucherNumber: String,
    val voucherStatus: VoucherStatus,
    val line: BankLineDetailResponse,
)

data class AutoMatchResponse(val matched: Int, val paired: Int, val statement: StatementResponse)

// ---- Bank reconciliation statement ----

data class ExcludedLineResponse(val id: Long, val statementId: Long, val date: LocalDate, val description: String,
    val moneyIn: BigDecimal, val moneyOut: BigDecimal, val reason: String?)

data class BrsResponse(
    val statement: StatementResponse,
    val bankClosing: BigDecimal,
    val bookBalance: BigDecimal,
    val unclearedReceipts: BigDecimal,
    val receipts: List<BookEntryResponse>,
    val unclearedPayments: BigDecimal,
    val payments: List<BookEntryResponse>,
    val excludedNet: BigDecimal,
    val excluded: List<ExcludedLineResponse>,
    // books − uncleared receipts + uncleared payments + set-aside lines.
    val explainedBalance: BigDecimal,
    val difference: BigDecimal,
    val canComplete: Boolean,
    val problems: List<String>,
    // Completed statements only: entries dated within it were added or changed since.
    val booksChanged: Boolean,
) {
    companion object {
        fun from(brs: Brs): BrsResponse {
            val open = brs.view.statement.status == StatementStatus.OPEN
            val problems = if (open) brs.problems() else emptyList()
            return BrsResponse(
                statement = StatementResponse.from(brs.view),
                bankClosing = brs.view.statement.closingBalance,
                bookBalance = brs.bookBalance,
                unclearedReceipts = brs.unclearedReceipts,
                receipts = brs.receipts.map(BookEntryResponse::from),
                unclearedPayments = brs.unclearedPayments,
                payments = brs.payments.map(BookEntryResponse::from),
                excludedNet = brs.excludedNet,
                excluded = brs.excluded.map {
                    ExcludedLineResponse(it.id!!, it.statementId, it.txnDate, it.description, it.credit, it.debit, it.excludeReason)
                },
                explainedBalance = brs.explainedBalance,
                difference = brs.difference,
                canComplete = open && problems.isEmpty(),
                problems = problems,
                booksChanged = brs.booksChanged,
            )
        }
    }
}

data class ReopenStatementRequest(@field:NotBlank @field:Size(max = 500) val reason: String?)
