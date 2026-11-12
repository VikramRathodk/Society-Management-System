package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.AmountStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

class StatementCsvTest {

    private fun money(text: String) = BigDecimal(text).setScale(2)

    private fun read(text: String, spec: MappingSpec) = StatementCsv.parse(StatementCsv.records(text, spec.delimiter), spec)

    // HDFC-style: account details above the header, two-digit years, separate withdrawal/deposit columns,
    // a closing balance, and a summary below the transactions.
    private val hdfc = """
        HDFC BANK Ltd.,,,,,,
        Account No :XXXXXXXX1234,,,,,,
        Statement From : 01/04/26 To : 30/04/26,,,,,,
        Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
        01/04/26,"UPI-RAMESH KUMAR-MAINT APR, FLAT 101",000412345678,01/04/26,,"2,500.00","52,500.00"
        03/04/26,NEFT-CR-SECURITY SERVICES,N093260001,03/04/26,"18,000.00",,"34,500.00"
        30/04/26,CHRG: SMS ALERT,,30/04/26,17.70,,"34,482.30"
        ,,,,,,
        STATEMENT SUMMARY :-,,,,,,
        Opening Balance,Dr Count,Cr Count,Debits,Credits,Closing Bal,
        "50,000.00",2,1,"18,017.70","2,500.00","34,482.30",
    """.trimIndent()

    @Test
    fun `reads an HDFC style export and skips the rows that aren't transactions`() {
        val spec = StatementCsv.suggest(StatementCsv.records(hdfc, ','), ',')!!
        assertEquals(4, spec.headerRow)
        assertEquals(1, spec.dateColumn)
        assertEquals("dd/MM/yy", spec.dateFormat)
        assertEquals(2, spec.descriptionColumn)
        assertEquals(3, spec.referenceColumn)
        assertEquals(AmountStyle.DEBIT_CREDIT, spec.amountStyle)
        assertEquals(5, spec.debitColumn)
        assertEquals(6, spec.creditColumn)
        assertEquals(7, spec.balanceColumn)

        val parsed = read(hdfc, spec)
        assertEquals(emptyList<RowNote>(), parsed.errors)
        assertEquals(3, parsed.lines.size)
        val first = parsed.lines[0]
        assertEquals(LocalDate.of(2026, 4, 1), first.date)
        assertEquals("UPI-RAMESH KUMAR-MAINT APR, FLAT 101", first.description)
        assertEquals("000412345678", first.reference)
        assertEquals(money("2500"), first.credit)
        assertEquals(money("0"), first.debit)
        assertEquals(money("52500"), first.balance)
        assertEquals(money("18000"), parsed.lines[1].debit)
        assertEquals(money("17.70"), parsed.lines[2].debit)
        // Summary rows have no date in column 1 (or words there), so they are noted, not imported.
        assertTrue(parsed.ignored.any { it.message.contains("STATEMENT SUMMARY") })
        assertEquals(emptyList<RowNote>(), parsed.warnings)
    }

    // SBI-style: tab separated, "1 Apr 2026" dates, spaces around amounts, Indian digit grouping.
    @Test
    fun `reads an SBI style tab separated export`() {
        val text = listOf(
            "Account Name\t:\tGREEN PARK CHS",
            "Txn Date\tValue Date\tDescription\tRef No./Cheque No.\tDebit\tCredit\tBalance",
            "1 Apr 2026\t1 Apr 2026\tBY TRANSFER-NEFT FROM FLAT 202\tTRF123\t \t 1,23,456.00\t1,73,456.00",
            "2 APR 2026\t2 Apr 2026\tTO CLG CHQ 000101\t000101\t 5,000.00\t \t1,68,456.00",
        ).joinToString("\n")
        assertEquals('\t', StatementCsv.detectDelimiter(text))
        val spec = StatementCsv.suggest(StatementCsv.records(text, '\t'), '\t')!!
        assertEquals(2, spec.headerRow)
        assertEquals(1, spec.dateColumn)
        assertEquals("dd MMM yyyy", spec.dateFormat)
        assertEquals(3, spec.descriptionColumn)
        assertEquals(4, spec.referenceColumn)
        assertEquals(5, spec.debitColumn)
        assertEquals(6, spec.creditColumn)

        val parsed = read(text, spec)
        assertEquals(emptyList<RowNote>(), parsed.errors)
        assertEquals(money("123456"), parsed.lines[0].credit)
        assertEquals(money("5000"), parsed.lines[1].debit)
        assertEquals(LocalDate.of(2026, 4, 2), parsed.lines[1].date)
    }

    // ICICI-style: one amount column and a Cr/Dr column, newest first, times after the dates.
    @Test
    fun `reads an amount with a Dr-Cr column and turns a newest-first file round`() {
        val text = """
            S No.,Transaction Date,Transaction Remarks,Amount,Cr/Dr,Balance
            1,05-04-2026 14:20:01,INT CREDIT,120.00,CR,"10,120.00"
            2,02-04-2026 09:00:00,ATM CASH,"2,000.00",DR,"10,000.00"
            3,01-04-2026 10:15:00,OPENING DEPOSIT,"12,000.00",Cr.,"12,000.00"
        """.trimIndent()
        val spec = StatementCsv.suggest(StatementCsv.records(text, ','), ',')!!
        assertEquals(AmountStyle.AMOUNT_TYPE, spec.amountStyle)
        assertEquals(2, spec.dateColumn)
        assertEquals("dd-MM-yyyy", spec.dateFormat)
        assertEquals(3, spec.descriptionColumn)
        assertEquals(4, spec.amountColumn)
        assertEquals(5, spec.typeColumn)

        val parsed = read(text, spec)
        assertEquals(emptyList<RowNote>(), parsed.errors)
        assertEquals(listOf(1, 2, 5), parsed.lines.map { it.date.dayOfMonth })
        assertEquals(money("12000"), parsed.lines[0].credit)
        assertEquals(money("2000"), parsed.lines[1].debit)
        assertEquals(emptyList<RowNote>(), parsed.warnings)
    }

    @Test
    fun `reads a signed amount column with semicolons, brackets and minus signs`() {
        val text = """
            Booking date;Details;Amount;Balance
            2026-04-01;Deposit;1000;1000
            2026-04-02;Bank charges;(25.50);974.50
            2026-04-03;Cheque 12;-100;874.50
        """.trimIndent()
        val spec = MappingSpec(';', 1, 1, "yyyy-MM-dd", 2, null, AmountStyle.SIGNED, null, null, 3, null, 4)
        val parsed = read(text, spec)
        assertEquals(emptyList<RowNote>(), parsed.errors)
        assertEquals(listOf(money("0"), money("25.50"), money("100")), parsed.lines.map { it.debit })
        assertEquals(money("1000"), parsed.lines[0].credit)
    }

    @Test
    fun `rows that can't be read are errors, and a wrong date format says so`() {
        val text = """
            Date,Narration,Withdrawal,Deposit
            01/04/2026,Both,10.00,20.00
            02/04/2026,Bad amount,12.345,
            03/04/2026,Words,ten,
            04/04/2026,Zero,0.00,0.00
            05/04/2026,Fine,,50
        """.trimIndent()
        val spec = MappingSpec(',', 1, 1, "dd/MM/yyyy", 2, null, AmountStyle.DEBIT_CREDIT, 3, 4, null, null, null)
        val parsed = read(text, spec)
        assertEquals(listOf(2, 3, 4), parsed.errors.map { it.row })
        assertTrue(parsed.errors[0].message.contains("Both a withdrawal and a deposit"))
        assertEquals(listOf(5), parsed.ignored.map { it.row })
        assertEquals(1, parsed.lines.size)

        val wrongFormat = read(text, spec.copy(dateFormat = "yyyy-MM-dd"))
        assertEquals(0, wrongFormat.lines.size)
        assertTrue(wrongFormat.errors.single().message.contains("No row has a date in the format yyyy-MM-dd"))
    }

    @Test
    fun `an unknown Dr-Cr value is an error`() {
        val text = "Date,Desc,Amt,Type\n01/04/2026,X,10,XX"
        val spec = MappingSpec(',', 1, 1, "dd/MM/yyyy", 2, null, AmountStyle.AMOUNT_TYPE, null, null, 3, 4, null)
        assertTrue(read(text, spec).errors.single().message.contains("neither Dr nor Cr"))
    }

    @Test
    fun `a running balance that doesn't follow is a warning`() {
        val text = "Date,Desc,Dr,Cr,Bal\n01/04/2026,A,,100,1100\n02/04/2026,B,50,,1000"
        val spec = MappingSpec(',', 1, 1, "dd/MM/yyyy", 2, null, AmountStyle.DEBIT_CREDIT, 3, 4, null, null, 5)
        val parsed = read(text, spec)
        assertEquals(listOf(3), parsed.warnings.map { it.row })
    }

    @Test
    fun `quoted cells keep separators, quotes and line breaks`() {
        val records = StatementCsv.records("a,\"b, c\",\"say \"\"hi\"\"\"\r\n\"multi\nline\",x\n", ',')
        assertEquals(listOf(listOf("a", "b, c", "say \"hi\""), listOf("multi\nline", "x")), records)
    }

    @Test
    fun `identical transactions get different fingerprints, and the same file gets the same ones`() {
        val text = "Date,Desc,Dr,Cr\n01/04/2026,UPI 500,,500\n01/04/2026,UPI 500,,500\n02/04/2026,UPI 500,,500"
        val spec = MappingSpec(',', 1, 1, "dd/MM/yyyy", 2, null, AmountStyle.DEBIT_CREDIT, 3, 4, null, null, null)
        val first = StatementCsv.fingerprints(read(text, spec).lines)
        assertEquals(3, first.toSet().size)
        assertEquals(first, StatementCsv.fingerprints(read(text, spec).lines))
        // A later file starting on the 2nd sees that row with the same fingerprint.
        val later = "Date,Desc,Dr,Cr\n02/04/2026,UPI 500,,500\n03/04/2026,Other,,10"
        assertEquals(first[2], StatementCsv.fingerprints(read(later, spec).lines)[0])
        assertNotEquals(first[0], first[1])
    }

    @Test
    fun `amounts and balances in the shapes banks write them`() {
        assertEquals(money("123456"), StatementCsv.readAmount("1,23,456.00"))
        assertEquals(money("500"), StatementCsv.readAmount("₹ 500"))
        assertEquals(money("500"), StatementCsv.readAmount("INR 500.00"))
        assertEquals(money("500"), StatementCsv.readAmount("Rs. 500"))
        assertEquals(money("-250.50"), StatementCsv.readAmount("(250.50)"))
        assertEquals(money("-10"), StatementCsv.readAmount("-10"))
        assertEquals(money("500"), StatementCsv.readAmount("500.00 Cr"))
        assertEquals(money("0"), StatementCsv.readAmount(" "))
        assertEquals(money("0"), StatementCsv.readAmount("-"))
        assertThrows<IllegalArgumentException> { StatementCsv.readAmount("1.234") }
        assertEquals(money("-1000"), StatementCsv.readBalance("1,000.00 Dr"))
        assertEquals(money("1000"), StatementCsv.readBalance("1,000.00 Cr"))
    }

    @Test
    fun `dates with one-digit days, month names in any case, and times`() {
        assertEquals(LocalDate.of(2026, 4, 1), StatementCsv.readDate("1/4/2026", "dd/MM/yyyy"))
        assertEquals(LocalDate.of(2026, 4, 1), StatementCsv.readDate("01-APR-2026", "dd-MMM-yyyy"))
        assertEquals(LocalDate.of(2026, 4, 1), StatementCsv.readDate("01-apr-26", "dd-MMM-yy"))
        assertEquals(LocalDate.of(2026, 4, 1), StatementCsv.readDate("01/04/2026 10:15:00 AM", "dd/MM/yyyy"))
        assertNull(StatementCsv.readDate("31/02/2026", "dd/MM/yyyy"))
        assertNull(StatementCsv.readDate("Opening Balance", "dd/MM/yyyy"))
    }

    @Test
    fun `decodes UTF-8 with a byte-order mark, and Windows-1252`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Date,₹".toByteArray(Charsets.UTF_8)
        assertEquals("Date,₹", StatementCsv.decode(bom))
        val latin = byteArrayOf('C'.code.toByte(), 0xE9.toByte())
        assertEquals("Cé", StatementCsv.decode(latin))
    }

    @Test
    fun `a mapping must name the columns its amount style needs`() {
        val spec = MappingSpec(',', 1, 1, "dd/MM/yyyy", 2, null, AmountStyle.DEBIT_CREDIT, 3, null, null, null, null)
        assertThrows<IllegalArgumentException> { spec.validate() }
        assertThrows<IllegalArgumentException> { spec.copy(creditColumn = 4, dateFormat = "dd/mm/yyyy").validate() }
        spec.copy(creditColumn = 4).validate()
    }
}
