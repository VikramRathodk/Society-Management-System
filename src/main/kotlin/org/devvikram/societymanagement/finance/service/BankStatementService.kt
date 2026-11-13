package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.common.storage.PhotoStorage
import org.devvikram.societymanagement.finance.model.AuditAction
import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.model.BankLine
import org.devvikram.societymanagement.finance.model.BankLineState
import org.devvikram.societymanagement.finance.model.BankStatement
import org.devvikram.societymanagement.finance.model.FinanceBankAccount
import org.devvikram.societymanagement.finance.model.StatementImport
import org.devvikram.societymanagement.finance.model.StatementMapping
import org.devvikram.societymanagement.finance.model.StatementStatus
import org.devvikram.societymanagement.finance.repository.AccountRepository
import org.devvikram.societymanagement.finance.repository.BooksRepository
import org.devvikram.societymanagement.finance.repository.BankMatchRepository
import org.devvikram.societymanagement.finance.repository.BankStatementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class CreateStatementCommand(
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
)

data class UpdateStatementCommand(
    val periodFrom: LocalDate?,
    val periodTo: LocalDate?,
    val openingBalance: BigDecimal?,
    val closingBalance: BigDecimal?,
)

data class StatementView(
    val statement: BankStatement,
    val accountName: String,
    val lines: Int,
    val matched: Int,
    val unmatched: Int,
    val excluded: Int,
    // Money in and out on the imported lines.
    val credits: BigDecimal,
    val debits: BigDecimal,
    val imports: Int,
) {
    // What the closing balance would be from the imported lines alone.
    val linesClosing: BigDecimal get() = statement.openingBalance + credits - debits

    // Non-zero while lines are missing (or the balances were typed wrong).
    val linesDifference: BigDecimal get() = statement.closingBalance - linesClosing
}

data class PreviewResult(
    val delimiter: Char,
    // The first rows as the file has them, so the user can pick the header row and columns.
    val rows: List<List<String>>,
    val totalRows: Int,
    // GIVEN in the request, SAVED on the account, SUGGESTED from the column names, or NONE.
    val mappingSource: String,
    val mapping: MappingSpec?,
    val parsed: ParsedStatement?,
)

data class ImportResult(
    val statementImport: StatementImport,
    val outsidePeriod: List<RowNote>,
    val ignored: List<RowNote>,
    val warnings: List<RowNote>,
)

// Bank statements of a bank ledger: the CSV column mapping, statement periods, and importing their
// lines. Matching them to the books is in the reconciliation service; nothing here touches a voucher.
@Service
class BankStatementService(
    private val bankStatementRepository: BankStatementRepository,
    private val matchRepository: BankMatchRepository,
    private val accountRepository: AccountRepository,
    private val chartService: ChartService,
    private val booksService: BooksService,
    private val booksRepository: BooksRepository,
    private val storage: PhotoStorage,
    private val audit: FinanceAudit,
) {
    fun get(id: Long): BankStatement = bankStatementRepository.findById(id) ?: throw NoSuchElementException("Statement $id not found")

    fun getImport(id: Long): StatementImport = bankStatementRepository.findImport(id) ?: throw NoSuchElementException("Import $id not found")

    fun bank(accountId: Long): FinanceBankAccount =
        accountRepository.findBank(accountId) ?: throw NoSuchElementException("Bank account $accountId not found")

    fun societyOfAccount(accountId: Long): Long = bank(accountId).let { chartService.get(it.accountId).societyId }

    // ---- Column mapping ----

    fun mapping(accountId: Long): StatementMapping? {
        bank(accountId)
        return bankStatementRepository.findMapping(accountId)
    }

    @Transactional
    fun saveMapping(accountId: Long, spec: MappingSpec, userId: Long): StatementMapping {
        bank(accountId)
        spec.validate()
        val existing = bankStatementRepository.findMapping(accountId)
        val before = existing?.let { snapshot(specOf(it)) }
        val mapping = (existing ?: StatementMapping(
            accountId, spec.delimiter.toString(), spec.headerRow, spec.dateColumn, spec.dateFormat,
            spec.descriptionColumn, spec.referenceColumn, spec.amountStyle, spec.debitColumn, spec.creditColumn,
            spec.amountColumn, spec.typeColumn, spec.balanceColumn, userId,
        )).apply {
            delimiter = spec.delimiter.toString()
            headerRow = spec.headerRow
            dateColumn = spec.dateColumn
            dateFormat = spec.dateFormat
            descriptionColumn = spec.descriptionColumn
            referenceColumn = spec.referenceColumn
            amountStyle = spec.amountStyle
            debitColumn = spec.debitColumn
            creditColumn = spec.creditColumn
            amountColumn = spec.amountColumn
            typeColumn = spec.typeColumn
            balanceColumn = spec.balanceColumn
            updatedBy = userId
            updatedAt = Instant.now()
        }
        val after = snapshot(spec)
        if (before != after) {
            audit.record(societyOfAccount(accountId), AuditEntityType.BANK_ACCOUNT, accountId,
                if (existing == null) AuditAction.CREATE else AuditAction.UPDATE, userId,
                oldValues = before?.let { mapOf("statementMapping" to it) },
                newValues = mapOf("statementMapping" to after))
        }
        return bankStatementRepository.saveMapping(mapping)
    }

    // Reads a file without saving anything: the raw first rows, and the lines as a mapping reads them.
    // Mapping: the one given, else the account's saved one, else a guess from the column names.
    fun preview(accountId: Long, data: ByteArray, given: MappingSpec?): PreviewResult {
        bank(accountId)
        val text = readFile(data)
        val saved = bankStatementRepository.findMapping(accountId)?.let(::specOf)
        val delimiter = given?.delimiter ?: saved?.delimiter ?: StatementCsv.detectDelimiter(text)
        val rows = StatementCsv.records(text, delimiter)
        given?.validate()
        val (source, spec) = when {
            given != null -> "GIVEN" to given
            saved != null -> "SAVED" to saved
            else -> StatementCsv.suggest(rows, delimiter)?.let { "SUGGESTED" to it } ?: ("NONE" to null)
        }
        return PreviewResult(
            delimiter = delimiter,
            rows = rows.take(PREVIEW_ROWS).map { row -> row.map { it.take(200) } },
            totalRows = rows.size,
            mappingSource = source,
            mapping = spec,
            parsed = spec?.let { StatementCsv.parse(rows, it) },
        )
    }

    // ---- Statements ----

    fun list(societyId: Long, accountId: Long?): List<StatementView> =
        bankStatementRepository.findBySociety(societyId, accountId).map(::view)

    fun view(id: Long): StatementView = view(get(id))

    fun imports(statementId: Long): List<StatementImport> {
        get(statementId)
        return bankStatementRepository.findImports(statementId)
    }

    // Periods of an account follow each other with no gap, each opening where the last one closed, and
    // only one is open at a time. The first may start on any day from the books' start.
    @Transactional
    fun create(accountId: Long, command: CreateStatementCommand, userId: Long, today: LocalDate): Long {
        val societyId = societyOfAccount(accountId)
        booksRepository.lockSociety(societyId)
        val books = booksService.requireBooks(societyId)
        checkPeriod(command.periodFrom, command.periodTo, today)
        val latest = bankStatementRepository.findLatest(accountId)
        if (latest == null) {
            require(!command.periodFrom.isBefore(books.startDate)) { "A statement can't start before the books start (${books.startDate})" }
            requireFirstOpening(accountId, command.periodFrom, command.openingBalance, books.startDate)
        } else {
            if (latest.status == StatementStatus.OPEN) {
                throw ConflictException("Finish the statement ${latest.periodFrom} to ${latest.periodTo} first")
            }
            require(command.periodFrom == latest.periodTo.plusDays(1)) {
                "This statement must start on ${latest.periodTo.plusDays(1)}, the day after the last one ended"
            }
            require(command.openingBalance.compareTo(latest.closingBalance) == 0) {
                "The opening balance must be ${latest.closingBalance.toPlainString()}, the last statement's closing balance"
            }
        }
        val statement = bankStatementRepository.save(
            BankStatement(societyId, accountId, command.periodFrom, command.periodTo,
                money(command.openingBalance), money(command.closingBalance), userId)
        )
        audit.record(societyId, AuditEntityType.RECONCILIATION, statement.id, AuditAction.CREATE, userId,
            newValues = statementSnapshot(statement) + ("account" to chartService.get(accountId).name))
        return statement.id!!
    }

    // While open. The start and opening balance only move on an account's first statement; lines already
    // imported must stay inside the period.
    @Transactional
    fun update(id: Long, command: UpdateStatementCommand, userId: Long, today: LocalDate) {
        val statement = requireOpen(id)
        booksRepository.lockSociety(statement.societyId)
        val first = bankStatementRepository.findBySociety(statement.societyId, statement.accountId).none { it.id != id }
        val before = statementSnapshot(statement)
        if (!first) {
            require(command.periodFrom == null || command.periodFrom == statement.periodFrom) {
                "Only an account's first statement can change its start; this one follows the last statement"
            }
            require(command.openingBalance == null || command.openingBalance.compareTo(statement.openingBalance) == 0) {
                "Only an account's first statement can change its opening balance"
            }
        }
        val from = command.periodFrom ?: statement.periodFrom
        val to = command.periodTo ?: statement.periodTo
        checkPeriod(from, to, today)
        if (first) {
            val books = booksService.requireBooks(statement.societyId)
            require(!from.isBefore(books.startDate)) { "A statement can't start before the books start (${books.startDate})" }
            requireFirstOpening(statement.accountId, from, command.openingBalance ?: statement.openingBalance, books.startDate)
        }
        val outside = bankStatementRepository.findLines(id, null).filter { it.txnDate < from || it.txnDate > to }
        require(outside.isEmpty()) { "${outside.size} imported line(s) fall outside $from to $to; remove that import first" }
        val clearedAfter = matchRepository.entries(matchRepository.findByStatement(id).map { it.financeLineId }).filter { it.date > to }
        require(clearedAfter.isEmpty()) { "${clearedAfter.first().number} is dated after $to and cleared on this statement; unmatch it first" }
        statement.periodFrom = from
        statement.periodTo = to
        command.openingBalance?.let { statement.openingBalance = money(it) }
        command.closingBalance?.let { statement.closingBalance = money(it) }
        val after = statementSnapshot(statement)
        if (after == before) return
        bankStatementRepository.save(statement)
        audit.record(statement.societyId, AuditEntityType.RECONCILIATION, id, AuditAction.UPDATE, userId,
            oldValues = before.filter { (k, v) -> after[k] != v }, newValues = after.filter { (k, v) -> before[k] != v })
    }

    // An open statement can be thrown away with its imports, lines and matches (never a voucher).
    @Transactional
    fun delete(id: Long, userId: Long) {
        val statement = requireOpen(id)
        booksRepository.lockSociety(statement.societyId)
        val keys = bankStatementRepository.findImports(id).map { it.storageKey }
        bankStatementRepository.delete(statement)
        keys.forEach(storage::delete)
        audit.record(statement.societyId, AuditEntityType.RECONCILIATION, id, AuditAction.DELETE, userId,
            oldValues = statementSnapshot(statement))
    }

    // ---- Imports ----

    // The whole file is refused if any row can't be read. Rows already imported for this bank account
    // (in any statement) are skipped, and so are rows outside the statement's period.
    @Transactional
    fun import(statementId: Long, data: ByteArray, filename: String?, userId: Long): ImportResult {
        val statement = requireOpen(statementId)
        booksRepository.lockSociety(statement.societyId)
        val mapping = bankStatementRepository.findMapping(statement.accountId)?.let(::specOf)
            ?: throw IllegalStateException("Set up how this bank's statement reads (the column mapping) first")
        val parsed = StatementCsv.parse(StatementCsv.records(readFile(data), mapping.delimiter), mapping)
        if (parsed.errors.isNotEmpty()) {
            throw IllegalArgumentException(
                "Can't read ${parsed.errors.size} row(s): " +
                    parsed.errors.take(5).joinToString("; ") { "row ${it.row}: ${it.message}" } +
                    if (parsed.errors.size > 5) "; …" else ""
            )
        }
        val (inside, outside) = parsed.lines.partition { it.date in statement.periodFrom..statement.periodTo }
        val fingerprints = StatementCsv.fingerprints(parsed.lines)
        val byLine = parsed.lines.zip(fingerprints).toMap()
        val existing = bankStatementRepository.existingFingerprints(statement.accountId, fingerprints)
        val fresh = inside.filter { byLine.getValue(it) !in existing }
        val duplicates = inside.size - fresh.size
        if (fresh.isEmpty()) {
            throw ConflictException(
                "Nothing new in this file: $duplicates row(s) already imported, ${outside.size} outside " +
                    "${statement.periodFrom} to ${statement.periodTo}"
            )
        }
        val name = filename?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.take(255)?.ifEmpty { null }
        val statementImport = bankStatementRepository.saveImport(
            StatementImport(
                statementId = statementId,
                filename = name,
                storageKey = storage.save(data, "text/csv"),
                sizeBytes = data.size,
                rowsImported = fresh.size,
                rowsDuplicate = duplicates,
                rowsIgnored = parsed.ignored.size + outside.size,
                importedBy = userId,
            )
        )
        var lineNo = bankStatementRepository.maxLineNo(statementId)
        bankStatementRepository.saveLines(fresh.map { line ->
            BankLine(
                statementId = statementId,
                importId = statementImport.id!!,
                accountId = statement.accountId,
                lineNo = ++lineNo,
                txnDate = line.date,
                description = line.description,
                reference = line.reference,
                debit = money(line.debit),
                credit = money(line.credit),
                balance = line.balance?.let(::money),
                fingerprint = byLine.getValue(line),
            )
        })
        audit.record(statement.societyId, AuditEntityType.RECONCILIATION, statementId, AuditAction.IMPORT, userId,
            newValues = mapOf("import" to statementImport.id, "filename" to name, "imported" to fresh.size,
                "duplicates" to duplicates, "ignored" to statementImport.rowsIgnored))
        return ImportResult(
            statementImport,
            outsidePeriod = outside.map { RowNote(it.row, "${it.date} is outside ${statement.periodFrom} to ${statement.periodTo}") },
            ignored = parsed.ignored,
            warnings = parsed.warnings,
        )
    }

    // Takes an import's lines back out (a wrong file or mapping), while the statement is open.
    @Transactional
    fun deleteImport(importId: Long, userId: Long) {
        val statementImport = getImport(importId)
        val statement = requireOpen(statementImport.statementId)
        booksRepository.lockSociety(statement.societyId)
        val lines = bankStatementRepository.countLinesByImport(importId)
        bankStatementRepository.deleteImport(statementImport)
        storage.delete(statementImport.storageKey)
        audit.record(statement.societyId, AuditEntityType.RECONCILIATION, statement.id, AuditAction.DELETE, userId,
            oldValues = mapOf("import" to importId, "filename" to statementImport.filename, "lines" to lines))
    }

    fun file(statementImport: StatementImport) =
        storage.load(statementImport.storageKey) ?: throw NoSuchElementException("Import ${statementImport.id} has no file")

    // ---- helpers ----

    private fun view(statement: BankStatement): StatementView {
        val lines = bankStatementRepository.findLines(statement.id!!, null)
        return StatementView(
            statement = statement,
            accountName = chartService.get(statement.accountId).name,
            lines = lines.size,
            matched = lines.count { it.state == BankLineState.MATCHED },
            unmatched = lines.count { it.state == BankLineState.UNMATCHED },
            excluded = lines.count { it.state == BankLineState.EXCLUDED },
            credits = lines.fold(BigDecimal.ZERO.setScale(2)) { sum, l -> sum + l.credit },
            debits = lines.fold(BigDecimal.ZERO.setScale(2)) { sum, l -> sum + l.debit },
            imports = bankStatementRepository.findImports(statement.id!!).size,
        )
    }

    fun requireOpen(id: Long): BankStatement = get(id).also {
        if (it.status != StatementStatus.OPEN) throw ConflictException("The statement ${it.periodFrom} to ${it.periodTo} is completed; reopen it first")
    }

    // Reconciling starts with an account's first statement: entries before it count as cleared, which is
    // only true if the bank and the books agreed that day.
    private fun requireFirstOpening(accountId: Long, from: LocalDate, opening: BigDecimal, booksStart: LocalDate) {
        val books = matchRepository.balanceAtStartOf(accountId, from)
        require(opening.compareTo(books) == 0) {
            "The first statement must open at ${books.toPlainString()}, this account's balance in the books at the start " +
                "of $from (entries before it count as cleared). If entries before then hadn't reached the bank yet, " +
                "start the first statement earlier (the books start $booksStart); otherwise correct the opening balance."
        }
    }

    private fun checkPeriod(from: LocalDate, to: LocalDate, today: LocalDate) {
        require(!to.isBefore(from)) { "The statement can't end before it starts" }
        require(!to.isAfter(today)) { "The statement can't end in the future" }
        require(!to.isAfter(from.plusYears(1).minusDays(1))) { "A statement covers at most one year" }
    }

    private fun readFile(data: ByteArray): String {
        require(data.isNotEmpty()) { "The file is empty" }
        require(data.size <= MAX_BYTES) { "Statement files can be at most 5 MB" }
        val text = StatementCsv.decode(data)
        require(text.none { it == '\u0000' }) { "This isn't a CSV file; save the statement as CSV from the bank's site" }
        return text
    }

    private fun money(value: BigDecimal): BigDecimal {
        require(value.scale() <= 2 || value.stripTrailingZeros().scale() <= 2) { "Amounts have at most 2 decimals" }
        require(value.abs() < BigDecimal("1000000000000")) { "Amount too large" }
        return value.setScale(2, java.math.RoundingMode.UNNECESSARY)
    }

    private fun statementSnapshot(statement: BankStatement): Map<String, Any?> = mapOf(
        "periodFrom" to statement.periodFrom.toString(),
        "periodTo" to statement.periodTo.toString(),
        "openingBalance" to statement.openingBalance.toPlainString(),
        "closingBalance" to statement.closingBalance.toPlainString(),
    )

    private fun snapshot(spec: MappingSpec): Map<String, Any?> = mapOf(
        "delimiter" to spec.delimiter.toString(), "headerRow" to spec.headerRow, "dateColumn" to spec.dateColumn,
        "dateFormat" to spec.dateFormat, "descriptionColumn" to spec.descriptionColumn,
        "referenceColumn" to spec.referenceColumn, "amountStyle" to spec.amountStyle.name,
        "debitColumn" to spec.debitColumn, "creditColumn" to spec.creditColumn, "amountColumn" to spec.amountColumn,
        "typeColumn" to spec.typeColumn, "balanceColumn" to spec.balanceColumn,
    )

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val PREVIEW_ROWS = 30

        fun specOf(mapping: StatementMapping) = MappingSpec(
            delimiter = mapping.delimiter.single(),
            headerRow = mapping.headerRow,
            dateColumn = mapping.dateColumn,
            dateFormat = mapping.dateFormat,
            descriptionColumn = mapping.descriptionColumn,
            referenceColumn = mapping.referenceColumn,
            amountStyle = mapping.amountStyle,
            debitColumn = mapping.debitColumn,
            creditColumn = mapping.creditColumn,
            amountColumn = mapping.amountColumn,
            typeColumn = mapping.typeColumn,
            balanceColumn = mapping.balanceColumn,
        )
    }
}
