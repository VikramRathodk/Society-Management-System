package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.AmountStyle
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

// How to read one bank's CSV export. Columns are 1-based. Same fields as the saved StatementMapping, so a
// mapping can be tried in a preview before it is saved.
data class MappingSpec(
    val delimiter: Char,
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
    fun validate() {
        require(delimiter in StatementCsv.DELIMITERS) { "The separator must be a comma, semicolon, tab or |" }
        require(headerRow in 1..100) { "The header row must be between 1 and 100" }
        require(dateFormat in StatementCsv.DATE_FORMATS) { "Unknown date format $dateFormat" }
        val columns = listOfNotNull(dateColumn, descriptionColumn, referenceColumn, debitColumn, creditColumn, amountColumn, typeColumn, balanceColumn)
        require(columns.all { it in 1..100 }) { "Columns are numbered 1 to 100" }
        when (amountStyle) {
            AmountStyle.DEBIT_CREDIT -> require(debitColumn != null && creditColumn != null && debitColumn != creditColumn) {
                "Give separate withdrawal and deposit columns"
            }
            AmountStyle.AMOUNT_TYPE -> require(amountColumn != null && typeColumn != null && amountColumn != typeColumn) {
                "Give the amount column and the Dr/Cr column"
            }
            AmountStyle.SIGNED -> require(amountColumn != null) { "Give the amount column" }
        }
        require(dateColumn != descriptionColumn) { "The date and description must be different columns" }
    }
}

// A transaction read from the file. `row` is the file's line number (1-based) for messages.
data class ParsedLine(
    val row: Int,
    val date: LocalDate,
    val description: String,
    val reference: String?,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val balance: BigDecimal?,
)

data class RowNote(val row: Int, val message: String)

data class ParsedStatement(
    // Oldest first, whichever order the bank listed them in.
    val lines: List<ParsedLine>,
    // Rows that aren't transactions (opening balance, totals, footers) or carry no amount.
    val ignored: List<RowNote>,
    // Rows that look like transactions but can't be read. A file with errors is not imported.
    val errors: List<RowNote>,
    // The running balance doesn't follow from the amounts (worth a look, not an error).
    val warnings: List<RowNote>,
)

// Reads bank statement CSVs. Pure: no database, so the parsing rules are unit-tested on their own.
object StatementCsv {
    val DELIMITERS = setOf(',', ';', '\t', '|')

    // Formats Indian banks use. Days and months may have one or two digits; month names in any case.
    val DATE_FORMATS = listOf(
        "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "dd/MM/yy", "dd-MM-yy",
        "dd-MMM-yyyy", "dd-MMM-yy", "dd MMM yyyy", "dd/MMM/yyyy", "dd MMM yy",
        "yyyy-MM-dd", "MM/dd/yyyy",
    )

    private val TIME_SUFFIX = Regex("""[\sT]+\d{1,2}:\d{2}(:\d{2})?(\.\d+)?(\s*[AaPp][Mm])?$""")
    private val NUMBER = Regex("""^\d+(\.\d{1,2})?$""")
    private val formatters = DATE_FORMATS.associateWith(::formatter)

    // UTF-8 (with or without a byte-order mark); anything that isn't valid UTF-8 is read as Windows-1252,
    // which is what older bank exports use.
    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            String(body, Charset.forName("windows-1252"))
        }
    }

    // RFC 4180 records: quoted cells may hold the separator, quotes ("") and line breaks.
    fun records(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"'); i++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(c)
                }
            } else when (c) {
                '"' -> if (cell.isBlank()) { cell.setLength(0); quoted = true } else cell.append(c)
                delimiter -> { row.add(cell.toString()); cell.setLength(0) }
                '\r', '\n' -> {
                    row.add(cell.toString()); cell.setLength(0)
                    rows.add(row); row = mutableListOf()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString()); rows.add(row)
        }
        return rows
    }

    // The separator that splits the first lines most consistently into several cells.
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(30).toList()
        return DELIMITERS.maxByOrNull { d -> sample.count { line -> line.count { it == d } >= 2 } * 100 + sample.sumOf { line -> line.count { it == d } }.coerceAtMost(99) }
            ?: ','
    }

    fun parse(rows: List<List<String>>, spec: MappingSpec): ParsedStatement {
        val formatter = formatters.getValue(spec.dateFormat)
        val lines = mutableListOf<ParsedLine>()
        val ignored = mutableListOf<RowNote>()
        val errors = mutableListOf<RowNote>()
        rows.forEachIndexed { index, cells ->
            val rowNo = index + 1
            if (rowNo <= spec.headerRow || cells.all { it.isBlank() }) return@forEachIndexed
            fun cell(column: Int?): String? = column?.let { cells.getOrNull(it - 1)?.trim() }
            val dateText = cell(spec.dateColumn).orEmpty()
            val date = readDate(dateText, formatter)
            if (date == null) {
                ignored.add(RowNote(rowNo, if (dateText.isEmpty()) "No date" else "\"${dateText.take(40)}\" isn't a date"))
                return@forEachIndexed
            }
            try {
                val (debit, credit) = amounts(spec, ::cell)
                if (debit.signum() == 0 && credit.signum() == 0) {
                    ignored.add(RowNote(rowNo, "No amount"))
                    return@forEachIndexed
                }
                val balance = cell(spec.balanceColumn)?.takeIf { it.isNotEmpty() && it != "-" }?.let { readBalance(it) }
                lines.add(
                    ParsedLine(
                        row = rowNo,
                        date = date,
                        description = cell(spec.descriptionColumn).orEmpty().replace(Regex("\\s+"), " ").take(500)
                            .ifEmpty { "(no description)" },
                        reference = cell(spec.referenceColumn)?.replace(Regex("\\s+"), " ")?.take(100)?.ifEmpty { null },
                        debit = debit,
                        credit = credit,
                        balance = balance,
                    )
                )
            } catch (e: IllegalArgumentException) {
                errors.add(RowNote(rowNo, e.message ?: "Can't read this row"))
            }
        }
        if (lines.isEmpty() && errors.isEmpty() && ignored.isNotEmpty()) {
            errors.add(RowNote(spec.headerRow + 1, "No row has a date in the format ${spec.dateFormat} in column ${spec.dateColumn}"))
        }
        // Newest-first exports are turned round so lines and running balances read oldest first.
        val ordered = if (lines.size > 1 && lines.first().date > lines.last().date) lines.reversed() else lines
        return ParsedStatement(ordered, ignored, errors, balanceWarnings(ordered))
    }

    // One fingerprint per bank transaction: the nth identical row of a file (two ₹500 UPI payments from
    // the same person on one day) gets its own, so a file imported twice adds nothing the second time.
    fun fingerprints(lines: List<ParsedLine>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return lines.map { line ->
            val key = listOf(
                line.date, line.debit.toPlainString(), line.credit.toPlainString(), line.balance?.toPlainString().orEmpty(),
                line.description.uppercase(), line.reference.orEmpty().uppercase(),
            ).joinToString("|")
            val n = seen.merge(key, 1, Int::plus)!!
            sha256("$key|$n")
        }
    }

    // A first guess from the column names (Txn Date, Narration, Chq./Ref.No., Withdrawal Amt., Deposit
    // Amt., Balance...), for the user to check. Null when no row looks like a header.
    fun suggest(rows: List<List<String>>, delimiter: Char): MappingSpec? {
        rows.take(30).forEachIndexed { index, cells ->
            val names = cells.map { it.trim().lowercase() }
            fun find(regex: Regex, except: Set<Int> = emptySet()): Int? =
                names.indices.firstOrNull { it + 1 !in except && regex.containsMatchIn(names[it]) }?.plus(1)
            val type = find(Regex("""(dr\s*/\s*cr|cr\s*/\s*dr|debit\s*/\s*credit|credit\s*/\s*debit|^type$|txn type)"""))
            val taken = setOfNotNull(type)
            val date = find(Regex("""^(txn|tran|transaction|posting)?\.?\s*date"""), taken)
                ?: find(Regex("""date"""), taken) ?: return@forEachIndexed
            val balance = find(Regex("""balance"""), taken + date)
            val debit = find(Regex("""(withdrawal|debit|^dr\b|paid out)"""), taken + date + setOfNotNull(balance))
            val credit = find(Regex("""(deposit|credit|^cr\b|paid in)"""), taken + date + setOfNotNull(balance, debit))
            val amount = find(Regex("""amount|amt"""), taken + date + setOfNotNull(balance, debit, credit))
            val description = find(Regex("""(narration|description|particulars|remarks|details)"""), taken + date)
                ?: return@forEachIndexed
            val reference = find(Regex("""(ref|chq|cheque|utr)"""), taken + setOfNotNull(date, description, debit, credit, amount, balance))
            val style = when {
                debit != null && credit != null -> AmountStyle.DEBIT_CREDIT
                amount != null && type != null -> AmountStyle.AMOUNT_TYPE
                amount != null -> AmountStyle.SIGNED
                else -> return@forEachIndexed
            }
            val dates = rows.drop(index + 1).take(15).mapNotNull { it.getOrNull(date - 1) }.filter { it.isNotBlank() }
            val format = DATE_FORMATS.maxByOrNull { f -> dates.count { readDate(it, f) != null } } ?: DATE_FORMATS.first()
            return MappingSpec(
                delimiter = delimiter,
                headerRow = index + 1,
                dateColumn = date,
                dateFormat = format,
                descriptionColumn = description,
                referenceColumn = reference,
                amountStyle = style,
                debitColumn = debit.takeIf { style == AmountStyle.DEBIT_CREDIT },
                creditColumn = credit.takeIf { style == AmountStyle.DEBIT_CREDIT },
                amountColumn = amount.takeIf { style != AmountStyle.DEBIT_CREDIT },
                typeColumn = type.takeIf { style == AmountStyle.AMOUNT_TYPE },
                balanceColumn = balance,
            )
        }
        return null
    }

    // Money: commas anywhere (1,23,456.00), a currency mark, spaces, a trailing Dr/Cr; brackets or a
    // leading minus for negative. At most two decimals.
    fun readAmount(text: String): BigDecimal {
        var s = text.trim().replace(",", "").replace("₹", "").replace(Regex("(?i)^(INR|Rs\\.?)"), "")
            .replace(Regex("(?i)(INR)$"), "").replace(" ", "").replace(" ", "")
        if (s.isEmpty() || s == "-") return BigDecimal.ZERO.setScale(2)
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1) }
        if (s.startsWith("-")) { negative = !negative; s = s.substring(1) } else if (s.startsWith("+")) s = s.substring(1)
        s = s.replace(Regex("(?i)(dr|cr)\\.?$"), "")
        require(NUMBER.matches(s)) { "\"${text.trim().take(30)}\" isn't an amount" }
        val value = BigDecimal(s).setScale(2, RoundingMode.UNNECESSARY)
        return if (negative) value.negate() else value
    }

    // A running balance; "Dr" after it means overdrawn.
    fun readBalance(text: String): BigDecimal {
        val value = readAmount(text)
        return if (Regex("(?i)dr\\.?\\s*$").containsMatchIn(text.trim())) value.abs().negate() else value
    }

    fun readDate(text: String, format: String): LocalDate? = readDate(text, formatters.getValue(format))

    private fun readDate(text: String, formatter: DateTimeFormatter): LocalDate? {
        val s = text.trim().replace(TIME_SUFFIX, "")
        if (s.isEmpty()) return null
        return try {
            LocalDate.parse(s, formatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun amounts(spec: MappingSpec, cell: (Int?) -> String?): Pair<BigDecimal, BigDecimal> = when (spec.amountStyle) {
        AmountStyle.DEBIT_CREDIT -> {
            val debit = readAmount(cell(spec.debitColumn).orEmpty()).abs()
            val credit = readAmount(cell(spec.creditColumn).orEmpty()).abs()
            require(debit.signum() == 0 || credit.signum() == 0) { "Both a withdrawal and a deposit on one row" }
            debit to credit
        }
        AmountStyle.AMOUNT_TYPE -> {
            val amount = readAmount(cell(spec.amountColumn).orEmpty()).abs()
            val type = cell(spec.typeColumn).orEmpty().uppercase().replace(".", "").trim()
            when (type) {
                "DR", "D", "DEBIT", "WITHDRAWAL", "W" -> amount to BigDecimal.ZERO.setScale(2)
                "CR", "C", "CREDIT", "DEPOSIT" -> BigDecimal.ZERO.setScale(2) to amount
                else -> if (amount.signum() == 0) BigDecimal.ZERO.setScale(2) to BigDecimal.ZERO.setScale(2)
                    else throw IllegalArgumentException("\"${type.take(20)}\" is neither Dr nor Cr")
            }
        }
        AmountStyle.SIGNED -> {
            val amount = readAmount(cell(spec.amountColumn).orEmpty())
            if (amount.signum() < 0) amount.negate() to BigDecimal.ZERO.setScale(2) else BigDecimal.ZERO.setScale(2) to amount
        }
    }

    private fun balanceWarnings(lines: List<ParsedLine>): List<RowNote> {
        val warnings = mutableListOf<RowNote>()
        lines.zipWithNext().forEach { (previous, line) ->
            val before = previous.balance ?: return@forEach
            val after = line.balance ?: return@forEach
            if (before + line.credit - line.debit != after) {
                warnings.add(RowNote(line.row, "Balance $after doesn't follow from the previous balance $before"))
            }
        }
        return warnings
    }

    private fun formatter(pattern: String): DateTimeFormatter {
        val lenient = pattern.replace("MMM", "\u0000").replace("dd", "d").replace("MM", "M").replace("\u0000", "MMM")
            .replace("yyyy", "uuuu").replace("yy", "uu")
        return DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(lenient).toFormatter(Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
