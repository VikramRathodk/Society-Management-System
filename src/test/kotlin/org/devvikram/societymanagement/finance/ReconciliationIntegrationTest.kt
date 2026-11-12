package org.devvikram.societymanagement.finance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.finance.model.StatementStatus
import org.devvikram.societymanagement.finance.repository.BankStatementRepository
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Bank reconciliation step R2: matching statement lines to the bank ledger's entries. Books start
// 2026-04-01 with cash 10,000 and bank 50,000. Real local Postgres, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class ReconciliationIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var bankStatementRepository: BankStatementRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private var societyId = 0L
    private var bank = 0L
    private var cash = 0L
    private var hallRent = 0L
    private var security = 0L
    private var bankCharges = 0L
    private lateinit var committee: User
    private lateinit var secretary: User
    private lateinit var admin: User

    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))

    private val april = """
        Date,Narration,Ref,Withdrawal,Deposit
        02/04/2026,UPI-RAMESH-MAINT,UPI1,,2500
        05/04/2026,NEFT-SECURITY,N1,18000,
        10/04/2026,UPI-A,,,1000
        10/04/2026,UPI-B,,,1000
        20/04/2026,CLG DEPOSIT 5 CHQ,,,15000
        25/04/2026,WRONG CREDIT,,,500
        26/04/2026,REVERSAL WRONG CREDIT,,500,
        28/04/2026,ATM CASH,,2000,
        30/04/2026,CHRG SMS ALERT,,17.70,
    """.trimIndent()

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = societyId),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Matching Test Society").id!!
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")
        admin = user("ADMIN")
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/chart").andExpect(status().isCreated)
        bank = idOf(sendAs(secretary, "POST", "/api/societies/$societyId/finance/bank-accounts",
            """{"name":"HDFC Savings","bankName":"HDFC Bank","accountType":"SAVINGS"}"""))
        cash = accountId("CASH")
        hallRent = accountId("4210")
        security = accountId("5210")
        bankCharges = accountId("5440")
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/start",
            """{"startDate":"2026-04-01","openings":[{"accountId":$cash,"amount":10000},{"accountId":$bank,"amount":50000}]}""")
            .andExpect(status().isCreated)
        sendAs(secretary, "PUT", "/api/finance/bank-accounts/$bank/statement-mapping",
            """{"headerRow":1,"dateColumn":1,"dateFormat":"dd/MM/yyyy","descriptionColumn":2,"referenceColumn":3,
                "amountStyle":"DEBIT_CREDIT","debitColumn":4,"creditColumn":5}""").andExpect(status().isOk)
    }

    // ---- helpers ----

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun getAs(user: User, path: String) = mockMvc.perform(get(path).header("Authorization", bearer(user)))

    private fun sendAs(user: User, method: String, path: String, body: String? = null): ResultActions {
        val builder = when (method) {
            "POST" -> post(path)
            "PUT" -> put(path)
            "PATCH" -> patch(path)
            else -> delete(path)
        }.header("Authorization", bearer(user))
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun body(result: ResultActions): String = result.andReturn().response.contentAsString

    private fun idOf(result: ResultActions): Long = JsonPath.read<Number>(body(result), "$.id").toLong()

    private fun accountId(key: String): Long {
        val json = body(getAs(secretary, "/api/societies/$societyId/finance/accounts"))
        val bySystem = JsonPath.read<List<Number>>(json, "$[?(@.systemKey == '$key')].id")
        return bySystem.ifEmpty { JsonPath.read(json, "$[?(@.code == '$key')].id") }.single().toLong()
    }

    private fun receipt(amount: String, date: String, into: Long = bank, payee: String = "Member") = idOf(
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/receipts",
            """{"date":"$date","depositAccountId":$into,"payee":"$payee","lines":[{"accountId":$hallRent,"amount":$amount}]}""")
            .andExpect(status().isCreated)
    )

    private fun payment(amount: String, date: String) = idOf(
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/payments",
            """{"date":"$date","payingAccountId":$bank,"payee":"Shree Security","lines":[{"accountId":$security,"amount":$amount}]}""")
            .andExpect(status().isCreated)
    )

    private fun voidVoucher(id: Long) =
        sendAs(secretary, "POST", "/api/finance/vouchers/$id/void", """{"reason":"Cheque returned"}""").andExpect(status().isOk)

    private fun statement(to: String = "2026-04-30", closing: String = "47982.30") = idOf(
        sendAs(secretary, "POST", "/api/finance/bank-accounts/$bank/statements",
            """{"periodFrom":"2026-04-01","periodTo":"$to","openingBalance":50000,"closingBalance":$closing}""")
            .andExpect(status().isCreated)
    )

    private fun import(statementId: Long, text: String) = mockMvc.perform(
        multipart("/api/finance/statements/$statementId/imports")
            .file(MockMultipartFile("file", "statement.csv", "text/csv", text.toByteArray()))
            .header("Authorization", bearer(secretary))
    )

    private fun lineId(statementId: Long, description: String): Long =
        JsonPath.read<List<Number>>(body(getAs(secretary, "/api/finance/statements/$statementId/lines")),
            "$[?(@.description == '$description')].id").first().toLong()

    private fun lineIds(statementId: Long, description: String): List<Long> =
        JsonPath.read<List<Number>>(body(getAs(secretary, "/api/finance/statements/$statementId/lines")),
            "$[?(@.description == '$description')].id").map { it.toLong() }

    // The bank-ledger line of a voucher, from the uncleared (or cleared) entries of a statement.
    private fun entryLine(statementId: Long, voucherId: Long, cleared: Boolean = false): Long =
        JsonPath.read<List<Number>>(body(getAs(secretary, "/api/finance/statements/$statementId/book-entries?cleared=$cleared")),
            "$[?(@.voucherId == $voucherId)].lineId").single().toLong()

    // A voucher's line on one account, straight from the database.
    private fun lineOf(voucherId: Long, accountId: Long): Long =
        jdbcTemplate.queryForObject("select id from finance_line where voucher_id = ? and account_id = ?", Long::class.java, voucherId, accountId)!!

    private fun match(statementId: Long, lineId: Long, vararg bookLines: Long) =
        sendAs(secretary, "POST", "/api/finance/statements/$statementId/lines/$lineId/match",
            """{"bookLineIds":[${bookLines.joinToString(",")}]}""")

    private fun complete(statementId: Long) {
        val statement = bankStatementRepository.findById(statementId)!!
        statement.status = StatementStatus.COMPLETED
        statement.completedAt = Instant.now()
        statement.completedBy = secretary.id
        bankStatementRepository.save(statement)
    }

    // ---- tests ----

    @Test
    fun `an import matches what is certain and leaves the rest`() {
        val rent = receipt("2500", "2026-04-01")
        val wages = payment("18000", "2026-04-03")
        receipt("1000", "2026-04-09")
        val statement = statement()

        import(statement, april).andExpect(status().isCreated)
            .andExpect(jsonPath("$.import.rowsImported").value(9))
            .andExpect(jsonPath("$.autoMatched").value(2))
            .andExpect(jsonPath("$.statement.matched").value(2))
            .andExpect(jsonPath("$.statement.unmatched").value(7))

        getAs(committee, "/api/finance/statements/$statement/lines?state=MATCHED")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].description").value("UPI-RAMESH-MAINT"))
            .andExpect(jsonPath("$[0].auto").value(true))
            .andExpect(jsonPath("$[0].entries[0].voucherId").value(rent))
            .andExpect(jsonPath("$[0].entries[0].moneyIn").value(2500))
            .andExpect(jsonPath("$[1].entries[0].voucherId").value(wages))
            .andExpect(jsonPath("$[1].entries[0].moneyOut").value(18000))

        // Two bank lines of 1,000 and one entry: nothing is guessed. The opening balance never shows.
        getAs(committee, "/api/finance/statements/$statement/book-entries")
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].moneyIn").value(1000))
        getAs(committee, "/api/finance/statements/$statement/book-entries?cleared=true")
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].bankLineId").isNumber)
    }

    @Test
    fun `the matching window comes from the society's setting`() {
        receipt("2500", "2026-04-01")
        sendAs(admin, "PATCH", "/api/societies/$societyId/settings", """{"reconciliationMatchDays":0}""").andExpect(status().isOk)
        val statement = statement()
        import(statement, april).andExpect(jsonPath("$.autoMatched").value(0))
        sendAs(admin, "PATCH", "/api/societies/$societyId/settings", """{"reconciliationMatchDays":5}""").andExpect(status().isOk)
        sendAs(secretary, "POST", "/api/finance/statements/$statement/auto-match")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matched").value(1))
            .andExpect(jsonPath("$.statement.matched").value(1))
    }

    @Test
    fun `five cheques deposited together are suggested as a group and match one bank line`() {
        val cheques = (1..5).map { receipt("3000", "2026-04-15", payee = "Flat 10$it") }
        receipt("2000", "2026-04-16")
        receipt("1000", "2026-04-16")
        val statement = statement()
        import(statement, april)
        val deposit = lineId(statement, "CLG DEPOSIT 5 CHQ")

        val suggestions = body(getAs(committee, "/api/finance/statements/$statement/lines/$deposit/suggestions").andExpect(status().isOk))
        val groups = JsonPath.read<List<List<Map<String, Any>>>>(suggestions, "$.groups")
        assertTrue(groups.isNotEmpty())
        assertTrue(groups.all { group -> group.sumOf { (it["moneyIn"] as Number).toDouble() } == 15000.0 })
        // Fewest entries first: 3000×4 + 2000 + 1000 needs six, five cheques need five.
        assertEquals(cheques.toSet(), groups.first().map { (it["voucherId"] as Number).toLong() }.toSet())

        val lines = cheques.map { entryLine(statement, it) }
        match(statement, deposit, *lines.toLongArray())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("MATCHED"))
            .andExpect(jsonPath("$.auto").value(false))
            .andExpect(jsonPath("$.entries", hasSize<Any>(5)))
    }

    @Test
    fun `a match must add up, be uncleared, on this account and within the statement`() {
        val small = receipt("1000", "2026-04-09")
        val onCash = receipt("2500", "2026-04-01", into = cash)
        val late = receipt("2500", "2026-05-03")
        val statement = statement()
        import(statement, april)
        val (first, second) = lineIds(statement, "UPI-A") + lineIds(statement, "UPI-B")
        val ramesh = lineId(statement, "UPI-RAMESH-MAINT")

        // Suggestions show the one 1,000 entry for both lines.
        getAs(secretary, "/api/finance/statements/$statement/lines/$first/suggestions")
            .andExpect(jsonPath("$.single", hasSize<Any>(1)))
            .andExpect(jsonPath("$.single[0].daysApart").value(1))
            .andExpect(jsonPath("$.single[0].withinWindow").value(true))

        val smallLine = entryLine(statement, small)
        match(statement, ramesh, smallLine).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("come to ₹1000.00 but the bank line is ₹2500.00")))
        match(statement, first, smallLine).andExpect(status().isOk)
        match(statement, first, smallLine).andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("already matched")))
        match(statement, second, smallLine).andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("isn't waiting to clear")))

        // A cash entry and one dated after the statement can't clear this bank's April line.
        match(statement, ramesh, lineOf(onCash, cash)).andExpect(status().isConflict)
        match(statement, ramesh, lineOf(late, bank)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("dated after 2026-04-30")))

        // Unmatching puts both back.
        sendAs(secretary, "DELETE", "/api/finance/statements/$statement/lines/$first/match")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("UNMATCHED"))
            .andExpect(jsonPath("$.entries", hasSize<Any>(0)))
        match(statement, second, smallLine).andExpect(status().isOk)
    }

    @Test
    fun `bank charges, interest and transfers missing from the books are recorded from the line`() {
        val statement = statement()
        import(statement, april)
        val sms = lineId(statement, "CHRG SMS ALERT")
        val atm = lineId(statement, "ATM CASH")
        val wrong = lineId(statement, "WRONG CREDIT")

        val charge = body(sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$sms/voucher",
            """{"accountId":$bankCharges}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.voucherStatus").value("POSTED"))
            .andExpect(jsonPath("$.line.state").value("MATCHED"))
            .andExpect(jsonPath("$.line.voucherNumber", containsString("PV/")))
            .andExpect(jsonPath("$.line.entries[0].moneyOut").value(17.7)))
        val chargeId = JsonPath.read<Number>(charge, "$.voucherId").toLong()
        getAs(secretary, "/api/finance/vouchers/$chargeId")
            .andExpect(jsonPath("$.date").value("2026-04-30"))
            .andExpect(jsonPath("$.payee").value("CHRG SMS ALERT"))
            .andExpect(jsonPath("$.amount").value(17.7))

        // Cash drawn from the ATM is a transfer to the cash account.
        sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$atm/voucher", """{"accountId":$cash}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.line.voucherNumber", containsString("CV/")))
            .andExpect(jsonPath("$.line.state").value("MATCHED"))

        // Money in to a maintenance member account is refused by the voucher rules.
        sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$wrong/voucher",
            """{"accountId":${accountId("MEMBER_DUES")}}""")
            .andExpect(status().isBadRequest)

        // A matched line can't get another voucher.
        sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$sms/voucher", """{"accountId":$bankCharges}""")
            .andExpect(status().isConflict)
    }

    @Test
    fun `a voucher waiting for approval clears its line when approved, and frees it when rejected`() {
        sendAs(admin, "PATCH", "/api/societies/$societyId/settings", """{"financeApprovalAbove":100}""").andExpect(status().isOk)
        val statement = statement()
        import(statement, april)
        val atm = lineId(statement, "ATM CASH")
        val wrongReversal = lineId(statement, "REVERSAL WRONG CREDIT")

        // A payment above the limit waits; the line waits with it.
        val waiting = body(sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$wrongReversal/voucher",
            """{"accountId":$security,"payee":"Shree Security"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.voucherStatus").value("PENDING_APPROVAL"))
            .andExpect(jsonPath("$.line.state").value("UNMATCHED")))
        val voucherId = JsonPath.read<Number>(waiting, "$.voucherId").toLong()
        sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$wrongReversal/exclude", """{"reason":"x"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("waiting for approval")))
        sendAs(secretary, "POST", "/api/finance/statements/$statement/auto-match").andExpect(jsonPath("$.matched").value(0))

        sendAs(committee, "POST", "/api/finance/vouchers/$voucherId/approve").andExpect(status().isOk)
        getAs(secretary, "/api/finance/statements/$statement/lines?state=MATCHED")
            .andExpect(jsonPath("$[?(@.id == $wrongReversal)].voucherStatus").value("POSTED"))
            .andExpect(jsonPath("$[?(@.id == $wrongReversal)].entries[0].voucherId").value(voucherId.toInt()))

        // Contras never need approval, so use a payment again for the rejection.
        val second = JsonPath.read<Number>(body(sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$atm/voucher",
            """{"accountId":$security,"payee":"Someone"}""")), "$.voucherId").toLong()
        sendAs(committee, "POST", "/api/finance/vouchers/$second/reject", """{"reason":"It was an ATM withdrawal"}""").andExpect(status().isOk)
        getAs(secretary, "/api/finance/statements/$statement/lines")
            .andExpect(jsonPath("$[?(@.id == $atm)].state").value("UNMATCHED"))
            .andExpect(jsonPath("$[?(@.id == $atm)].voucherId", contains(nullValue())))
        sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$atm/voucher", """{"accountId":$cash}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.line.state").value("MATCHED"))
    }

    @Test
    fun `a bank error the bank reversed is set aside with a reason`() {
        val statement = statement()
        import(statement, april)
        val wrong = lineId(statement, "WRONG CREDIT")
        sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$wrong/exclude", """{"reason":" "}""")
            .andExpect(status().isBadRequest)
        sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$wrong/exclude",
            """{"reason":"Credited by mistake, reversed by the bank on 26 April"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("EXCLUDED"))
            .andExpect(jsonPath("$.excludeReason", containsString("by mistake")))
        match(statement, wrong, 1).andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("set aside")))
        sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$wrong/include")
            .andExpect(jsonPath("$.state").value("UNMATCHED"))
            .andExpect(jsonPath("$.excludeReason").value(nullValue()))
        getAs(secretary, "/api/finance/statements/$statement")
            .andExpect(jsonPath("$.excluded").value(0))
    }

    @Test
    fun `an entry voided before it reached the bank cancels against its reversal`() {
        val mistake = receipt("700", "2026-04-12")
        voidVoucher(mistake)
        val other = receipt("300", "2026-04-13")
        val refund = payment("300", "2026-04-14")
        receipt("999", "2026-04-15")
        val statement = statement(to = today.toString())

        sendAs(secretary, "POST", "/api/finance/statements/$statement/auto-match")
            .andExpect(jsonPath("$.paired").value(1))
            .andExpect(jsonPath("$.matched").value(0))
        val cleared = body(getAs(secretary, "/api/finance/statements/$statement/book-entries?cleared=true"))
        assertEquals(2, JsonPath.read<List<Any>>(cleared, "$[?(@.pairLineId)]").size)
        assertEquals(listOf(true), JsonPath.read<List<Boolean>>(cleared, "$[?(@.reversal == true)].reversal"))

        // Any two entries that cancel exactly can be paired by hand; others can't.
        val otherLine = entryLine(statement, other)
        val refundLine = entryLine(statement, refund)
        val oddLine = JsonPath.read<List<Number>>(body(getAs(secretary, "/api/finance/statements/$statement/book-entries")),
            "$[?(@.moneyIn == 999)].lineId").single().toLong()
        sendAs(secretary, "POST", "/api/finance/statements/$statement/pairs", """{"firstLineId":$otherLine,"secondLineId":$oddLine}""")
            .andExpect(status().isBadRequest)
        sendAs(secretary, "POST", "/api/finance/statements/$statement/pairs", """{"firstLineId":$otherLine,"secondLineId":$refundLine}""")
            .andExpect(status().isNoContent)
        sendAs(secretary, "DELETE", "/api/finance/statements/$statement/pairs/$refundLine").andExpect(status().isNoContent)
        getAs(secretary, "/api/finance/statements/$statement/book-entries")
            .andExpect(jsonPath("$", hasSize<Any>(3)))
    }

    @Test
    fun `a bounced cheque stays matched to the deposit, and its reversal matches the return`() {
        val cheque = receipt("5000", "2026-04-02")
        val statement = statement(to = today.toString(), closing = "50000")
        import(statement, """
            Date,Narration,Ref,Withdrawal,Deposit
            03/04/2026,CLG CHQ 123456,123456,,5000
            10/04/2026,CHQ RETURN 123456,123456,5000,
        """.trimIndent()).andExpect(jsonPath("$.autoMatched").value(1))

        voidVoucher(cheque)
        // The deposit stays matched (the bank did see it); the reversal waits for the bank's return.
        getAs(secretary, "/api/finance/statements/$statement/lines?state=MATCHED")
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].entries[0].status").value("VOID"))
        val reversal = JsonPath.read<List<Number>>(body(getAs(secretary, "/api/finance/statements/$statement/book-entries")),
            "$[?(@.reversal == true)].lineId").single().toLong()
        val returned = lineId(statement, "CHQ RETURN 123456")
        sendAs(secretary, "POST", "/api/finance/statements/$statement/auto-match").andExpect(jsonPath("$.paired").value(0))
        match(statement, returned, reversal).andExpect(status().isOk)
        getAs(secretary, "/api/finance/statements/$statement")
            .andExpect(jsonPath("$.matched").value(2))
            .andExpect(jsonPath("$.unmatched").value(0))
    }

    @Test
    fun `nothing changes on a completed statement, and its end can't move before a cleared entry`() {
        // Written up two days after the bank received it.
        val rent = receipt("2500", "2026-04-04")
        val statement = statement()
        import(statement, "Date,Narration,Ref,Withdrawal,Deposit\n02/04/2026,UPI-RAMESH-MAINT,UPI1,,2500")
            .andExpect(jsonPath("$.autoMatched").value(1))
        val ramesh = lineId(statement, "UPI-RAMESH-MAINT")
        val number = JsonPath.read<String>(body(getAs(secretary, "/api/finance/vouchers/$rent")), "$.number")
        sendAs(secretary, "PATCH", "/api/finance/statements/$statement", """{"periodTo":"2026-04-03"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("$number is dated after 2026-04-03")))

        complete(statement)
        sendAs(secretary, "DELETE", "/api/finance/statements/$statement/lines/$ramesh/match").andExpect(status().isConflict)
        sendAs(secretary, "POST", "/api/finance/statements/$statement/auto-match").andExpect(status().isConflict)
        sendAs(secretary, "POST", "/api/finance/statements/$statement/lines/$ramesh/exclude", """{"reason":"x"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("is completed")))
    }

    @Test
    fun `the committee looks, only the secretary matches`() {
        receipt("1000", "2026-04-09")
        val statement = statement()
        import(statement, april)
        val first = lineIds(statement, "UPI-A").single()
        getAs(committee, "/api/finance/statements/$statement/book-entries").andExpect(status().isOk)
        getAs(committee, "/api/finance/statements/$statement/lines/$first/suggestions").andExpect(status().isOk)
        sendAs(committee, "POST", "/api/finance/statements/$statement/lines/$first/match", """{"bookLineIds":[1]}""")
            .andExpect(status().isForbidden)
        sendAs(committee, "POST", "/api/finance/statements/$statement/auto-match").andExpect(status().isForbidden)
        sendAs(committee, "POST", "/api/finance/statements/$statement/lines/$first/voucher", """{"accountId":$hallRent}""")
            .andExpect(status().isForbidden)
    }

    @Test
    fun `the database refuses a match that doesn't add up or is on another account`() {
        val small = receipt("1000", "2026-04-09")
        val onCash = receipt("2500", "2026-04-01", into = cash)
        val statement = statement()
        import(statement, april)
        val ramesh = lineId(statement, "UPI-RAMESH-MAINT")
        val smallLine = entryLine(statement, small)
        val insert = "insert into finance_bank_match (statement_id, bank_line_id, finance_line_id, auto, matched_by) values (?, ?, ?, false, ?)"

        // A failed statement aborts the transaction, so each attempt runs after a savepoint.
        jdbcTemplate.execute("savepoint before_account")
        val wrongAccount = assertThrows<Exception> { jdbcTemplate.update(insert, statement, ramesh, lineOf(onCash, cash), secretary.id) }
        assertTrue(wrongAccount.message!!.contains("not on the statement's bank account"), wrongAccount.message)
        jdbcTemplate.execute("rollback to savepoint before_account")

        // The total is checked at commit; made immediate here so the test sees it.
        jdbcTemplate.execute("set constraints all immediate")
        val wrongTotal = assertThrows<Exception> { jdbcTemplate.update(insert, statement, ramesh, smallLine, secretary.id) }
        assertTrue(wrongTotal.message!!.contains("does not match its book lines"), wrongTotal.message)
    }
}
