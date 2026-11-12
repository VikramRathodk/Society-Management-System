package org.devvikram.societymanagement.finance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

// Bank reconciliation step R3: the bank reconciliation statement (BRS), completing and reopening, and
// the cleared date on the bank ledger. Worked example, checked by hand (books start 2026-04-01, bank
// 50,000):
//
//   April books:  +2,500 rent (1 Apr)  −18,000 security (3 Apr)  +3,000 cheque (29 Apr, not yet in the
//                 bank)  −1,200 cheque issued (29 Apr, not yet presented)  −17.70 SMS charges (30 Apr,
//                 recorded from the bank line)                                    books 30 Apr: 36,282.30
//   April bank:   +2,500 (2 Apr)  −18,000 (5 Apr)  +500 wrong credit (25 Apr, set aside)  −17.70 (30 Apr)
//                                                                                  bank 30 Apr: 34,982.30
//   BRS April:    36,282.30 − 3,000 + 1,200 + 500 = 34,982.30, difference 0
//   May bank:     +3,000 (2 May)  −500 the bank's reversal (3 May, set aside)  −1,200 (6 May)
//                                                                                  bank 31 May: 36,282.30
//   BRS May:      36,282.30 − 0 + 0 + (500 − 500) = 36,282.30, difference 0
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class BankReconciliationStatementIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private var societyId = 0L
    private var bank = 0L
    private var cash = 0L
    private var hallRent = 0L
    private var security = 0L
    private var bankCharges = 0L
    private lateinit var committee: User
    private lateinit var secretary: User

    private val aprilCsv = """
        Date,Narration,Ref,Withdrawal,Deposit
        02/04/2026,UPI-RENT,,,2500
        05/04/2026,NEFT-SECURITY,,18000,
        25/04/2026,WRONG CREDIT,,,500
        30/04/2026,CHRG SMS ALERT,,17.70,
    """.trimIndent()

    private val mayCsv = """
        Date,Narration,Ref,Withdrawal,Deposit
        02/05/2026,CLG CHQ 501,501,,3000
        03/05/2026,REVERSAL WRONG CREDIT,,500,
        06/05/2026,CHQ PAID 777,777,1200,
    """.trimIndent()

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String) = userService.createUser(
        CreateUserCommand(name = "$roleCode user", phone = randomPhone(), rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = societyId))
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("BRS Test Society").id!!
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")
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

    private fun receipt(amount: String, date: String) = idOf(
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/receipts",
            """{"date":"$date","depositAccountId":$bank,"payee":"Member","lines":[{"accountId":$hallRent,"amount":$amount}]}""")
            .andExpect(status().isCreated)
    )

    private fun payment(amount: String, date: String) = idOf(
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/payments",
            """{"date":"$date","payingAccountId":$bank,"payee":"Shree Security","lines":[{"accountId":$security,"amount":$amount}]}""")
            .andExpect(status().isCreated)
    )

    private fun statement(from: String, to: String, opening: String, closing: String) =
        sendAs(secretary, "POST", "/api/finance/bank-accounts/$bank/statements",
            """{"periodFrom":"$from","periodTo":"$to","openingBalance":$opening,"closingBalance":$closing}""")

    private fun import(statementId: Long, text: String) = mockMvc.perform(
        multipart("/api/finance/statements/$statementId/imports")
            .file(MockMultipartFile("file", "statement.csv", "text/csv", text.toByteArray()))
            .header("Authorization", bearer(secretary))
    ).andExpect(status().isCreated)

    private fun lineId(statementId: Long, description: String): Long =
        JsonPath.read<List<Number>>(body(getAs(secretary, "/api/finance/statements/$statementId/lines")),
            "$[?(@.description == '$description')].id").single().toLong()

    private fun lineOf(voucherId: Long): Long =
        jdbcTemplate.queryForObject("select id from finance_line where voucher_id = ? and account_id = ?", Long::class.java, voucherId, bank)!!

    private fun exclude(statementId: Long, description: String) =
        sendAs(secretary, "POST", "/api/finance/statements/$statementId/lines/${lineId(statementId, description)}/exclude",
            """{"reason":"Bank credited this by mistake and reversed it"}""").andExpect(status().isOk)

    private var cheque = 0L
    private var issued = 0L

    // April as in the worked example, completed. Returns the statement id.
    private fun reconciledApril(): Long {
        receipt("2500", "2026-04-01")
        payment("18000", "2026-04-03")
        cheque = receipt("3000", "2026-04-29")
        issued = payment("1200", "2026-04-29")
        val april = idOf(statement("2026-04-01", "2026-04-30", "50000", "34982.30").andExpect(status().isCreated))
        import(april, aprilCsv).andExpect(jsonPath("$.autoMatched").value(2))
        sendAs(secretary, "POST", "/api/finance/statements/$april/lines/${lineId(april, "CHRG SMS ALERT")}/voucher",
            """{"accountId":$bankCharges}""").andExpect(status().isCreated)
        exclude(april, "WRONG CREDIT")
        return april
    }

    // ---- tests ----

    @Test
    fun `the April BRS explains the bank balance from the books, then completes`() {
        receipt("2500", "2026-04-01")
        payment("18000", "2026-04-03")
        receipt("3000", "2026-04-29")
        payment("1200", "2026-04-29")
        val april = idOf(statement("2026-04-01", "2026-04-30", "50000", "34982.30").andExpect(status().isCreated))
        import(april, aprilCsv)

        // Two lines still open: the BRS says so and completing is refused.
        getAs(committee, "/api/finance/statements/$april/brs")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.canComplete").value(false))
            .andExpect(jsonPath("$.problems[0]", containsString("2 bank line(s) still need matching")))
        sendAs(secretary, "POST", "/api/finance/statements/$april/complete")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("Can't complete yet: 2 bank line(s)")))

        sendAs(secretary, "POST", "/api/finance/statements/$april/lines/${lineId(april, "CHRG SMS ALERT")}/voucher",
            """{"accountId":$bankCharges}""").andExpect(status().isCreated)
        exclude(april, "WRONG CREDIT")

        getAs(committee, "/api/finance/statements/$april/brs")
            .andExpect(jsonPath("$.bankClosing").value(34982.3))
            .andExpect(jsonPath("$.bookBalance").value(36282.3))
            .andExpect(jsonPath("$.unclearedReceipts").value(3000))
            .andExpect(jsonPath("$.receipts", hasSize<Any>(1)))
            .andExpect(jsonPath("$.receipts[0].date").value("2026-04-29"))
            .andExpect(jsonPath("$.unclearedPayments").value(1200))
            .andExpect(jsonPath("$.payments", hasSize<Any>(1)))
            .andExpect(jsonPath("$.excludedNet").value(500))
            .andExpect(jsonPath("$.excluded[0].reason", containsString("by mistake")))
            .andExpect(jsonPath("$.explainedBalance").value(34982.3))
            .andExpect(jsonPath("$.difference").value(0))
            .andExpect(jsonPath("$.canComplete").value(true))
            .andExpect(jsonPath("$.problems", hasSize<Any>(0)))

        val csv = body(getAs(committee, "/api/finance/statements/$april/brs.csv").andExpect(status().isOk))
        assertTrue(csv.contains("Balance in the books,2026-04-30,,,36282.30"), csv)
        assertTrue(csv.contains("Less: receipt not yet in the bank,2026-04-29"), csv)
        assertTrue(csv.contains("Total payments not yet out of the bank,,,,1200.00"), csv)
        assertTrue(csv.contains("Balance as per bank statement,2026-04-30,,,34982.30"), csv)
        assertTrue(csv.contains("Difference,,,,0.00"), csv)

        sendAs(committee, "POST", "/api/finance/statements/$april/complete").andExpect(status().isForbidden)
        sendAs(secretary, "POST", "/api/finance/statements/$april/complete")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.statement.status").value("COMPLETED"))
            .andExpect(jsonPath("$.statement.completedAt").isNotEmpty)
            .andExpect(jsonPath("$.difference").value(0))
            .andExpect(jsonPath("$.canComplete").value(false))
            .andExpect(jsonPath("$.booksChanged").value(false))
        sendAs(secretary, "POST", "/api/finance/statements/$april/complete").andExpect(status().isConflict)
    }

    @Test
    fun `May clears what April carried over and the bank's reversal cancels the set-aside credit`() {
        val april = reconciledApril()
        sendAs(secretary, "POST", "/api/finance/statements/$april/complete").andExpect(status().isOk)

        val may = idOf(statement("2026-05-01", "2026-05-31", "34982.30", "36282.30").andExpect(status().isCreated))
        // Carried over from April: the cheque in (29 Apr, three days before 2 May: auto-matched) and the
        // cheque out (presented a week later: left to match by hand).
        import(may, mayCsv).andExpect(jsonPath("$.autoMatched").value(1))
        getAs(secretary, "/api/finance/statements/$may/book-entries")
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].voucherId").value(issued))
        getAs(secretary, "/api/finance/statements/$may/lines?state=MATCHED")
            .andExpect(jsonPath("$[0].entries[0].voucherId").value(cheque))
        sendAs(secretary, "POST", "/api/finance/statements/$may/lines/${lineId(may, "CHQ PAID 777")}/match",
            """{"bookLineIds":[${lineOf(issued)}]}""").andExpect(status().isOk)
        exclude(may, "REVERSAL WRONG CREDIT")

        getAs(secretary, "/api/finance/statements/$may/brs")
            .andExpect(jsonPath("$.bookBalance").value(36282.3))
            .andExpect(jsonPath("$.unclearedReceipts").value(0))
            .andExpect(jsonPath("$.unclearedPayments").value(0))
            .andExpect(jsonPath("$.excluded", hasSize<Any>(2)))
            .andExpect(jsonPath("$.excludedNet").value(0))
            .andExpect(jsonPath("$.difference").value(0))
        sendAs(secretary, "POST", "/api/finance/statements/$may/complete").andExpect(status().isOk)

        // April's BRS still shows April: the two cheques were outstanding then.
        getAs(secretary, "/api/finance/statements/$april/brs")
            .andExpect(jsonPath("$.receipts", hasSize<Any>(1)))
            .andExpect(jsonPath("$.payments", hasSize<Any>(1)))
            .andExpect(jsonPath("$.excludedNet").value(500))
            .andExpect(jsonPath("$.difference").value(0))
            .andExpect(jsonPath("$.booksChanged").value(false))
    }

    @Test
    fun `a closing balance the lines don't reach can't be completed`() {
        val april = reconciledApril()
        sendAs(secretary, "PATCH", "/api/finance/statements/$april", """{"closingBalance":35000}""").andExpect(status().isOk)
        getAs(secretary, "/api/finance/statements/$april/brs")
            .andExpect(jsonPath("$.difference").value(17.7))
            .andExpect(jsonPath("$.problems", hasSize<Any>(2)))
            .andExpect(jsonPath("$.problems[0]", containsString("the imported lines come to 34982.30 but the statement closes at 35000.00")))
        sendAs(secretary, "POST", "/api/finance/statements/$april/complete").andExpect(status().isConflict)
    }

    @Test
    fun `a completed BRS keeps its totals and says when the books changed under it`() {
        val april = reconciledApril()
        sendAs(secretary, "POST", "/api/finance/statements/$april/complete").andExpect(status().isOk)
        // A payment backdated into April after it was reconciled (no lock date stopped it).
        payment("999", "2026-04-20")
        getAs(secretary, "/api/finance/statements/$april/brs")
            .andExpect(jsonPath("$.bookBalance").value(36282.3))
            .andExpect(jsonPath("$.unclearedPayments").value(1200))
            .andExpect(jsonPath("$.payments", hasSize<Any>(2)))
            .andExpect(jsonPath("$.difference").value(0))
            .andExpect(jsonPath("$.booksChanged").value(true))
    }

    @Test
    fun `only the latest statement reopens, with a reason`() {
        val april = reconciledApril()
        sendAs(secretary, "POST", "/api/finance/statements/$april/reopen", """{"reason":"x"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("already open")))
        sendAs(secretary, "POST", "/api/finance/statements/$april/complete").andExpect(status().isOk)
        val may = idOf(statement("2026-05-01", "2026-05-31", "34982.30", "36282.30").andExpect(status().isCreated))

        sendAs(secretary, "POST", "/api/finance/statements/$april/reopen", """{"reason":"Wrong match"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("Delete the statement 2026-05-01 to 2026-05-31 first")))
        sendAs(secretary, "DELETE", "/api/finance/statements/$may").andExpect(status().isNoContent)

        sendAs(secretary, "POST", "/api/finance/statements/$april/reopen", """{"reason":" "}""").andExpect(status().isBadRequest)
        sendAs(committee, "POST", "/api/finance/statements/$april/reopen", """{"reason":"Wrong match"}""").andExpect(status().isForbidden)
        sendAs(secretary, "POST", "/api/finance/statements/$april/reopen", """{"reason":"The SMS charge was matched to the wrong line"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.completedAt").value(nullValue()))
            .andExpect(jsonPath("$.reopenReason", containsString("SMS charge")))
        // The matches stay; it can be changed and completed again.
        getAs(secretary, "/api/finance/statements/$april").andExpect(jsonPath("$.matched").value(3))
        sendAs(secretary, "DELETE", "/api/finance/statements/$april/lines/${lineId(april, "CHRG SMS ALERT")}/match")
            .andExpect(status().isOk)

        val audit = body(getAs(secretary, "/api/societies/$societyId/finance/audit?entityType=RECONCILIATION&entityId=$april"))
        val actions = JsonPath.read<List<String>>(audit, "$..action")
        assertTrue(actions.containsAll(listOf("COMPLETE", "REOPEN", "UNMATCH")), actions.toString())
        assertEquals(listOf("The SMS charge was matched to the wrong line"),
            JsonPath.read<List<String>>(audit, "$[?(@.action == 'REOPEN')].reason"))
    }

    @Test
    fun `a later completed statement blocks reopening an earlier one`() {
        val april = reconciledApril()
        sendAs(secretary, "POST", "/api/finance/statements/$april/complete").andExpect(status().isOk)
        val may = idOf(statement("2026-05-01", "2026-05-31", "34982.30", "34982.30").andExpect(status().isCreated))
        // Nothing moved in May, except the two April cheques that are still out: May can't complete
        // unless the books explain the balance, so match nothing and compare.
        getAs(secretary, "/api/finance/statements/$may/brs")
            .andExpect(jsonPath("$.unclearedReceipts").value(3000))
            .andExpect(jsonPath("$.unclearedPayments").value(1200))
            .andExpect(jsonPath("$.excludedNet").value(500))
            .andExpect(jsonPath("$.difference").value(0))
        sendAs(secretary, "POST", "/api/finance/statements/$may/complete").andExpect(status().isOk)
        sendAs(secretary, "POST", "/api/finance/statements/$april/reopen", """{"reason":"x"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("Only the latest statement (2026-05-01 to 2026-05-31)")))
    }

    @Test
    fun `the first statement opens where the books stood, and earlier entries count as cleared`() {
        receipt("2500", "2026-04-01")
        payment("18000", "2026-04-03")
        statement("2026-04-01", "2026-04-30", "49000", "34982.30")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("must open at 50000.00")))

        // Starting in May: April's entries are taken as cleared, so May opens at the books' 1 May balance.
        statement("2026-05-01", "2026-05-31", "50000", "34500").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("must open at 34500.00")))
        val may = idOf(statement("2026-05-01", "2026-05-31", "34500", "34500").andExpect(status().isCreated))
        getAs(secretary, "/api/finance/statements/$may/book-entries").andExpect(jsonPath("$", hasSize<Any>(0)))
        getAs(secretary, "/api/finance/statements/$may/brs")
            .andExpect(jsonPath("$.bookBalance").value(34500))
            .andExpect(jsonPath("$.difference").value(0))
        sendAs(secretary, "POST", "/api/finance/statements/$may/complete").andExpect(status().isOk)
    }

    @Test
    fun `the bank ledger shows when each entry cleared`() {
        val april = reconciledApril()
        val ledger = body(getAs(secretary, "/api/finance/accounts/$bank/ledger?from=2026-04-01&to=2026-04-30").andExpect(status().isOk))
        fun clearedOn(number: String): Any? = JsonPath.read<List<Any?>>(ledger, "$.lines[?(@.voucherNumber == '$number')].clearedOn").single()
        val numbers = JsonPath.read<List<String>>(ledger, "$.lines[*].voucherNumber")
        val dates = numbers.associateWith(::clearedOn)
        assertEquals("2026-04-01", dates.entries.first { it.key.startsWith("OB") }.value)
        assertEquals(listOf("2026-04-02", "2026-04-05", null, null, "2026-04-30"),
            numbers.filterNot { it.startsWith("OB") }.map(dates::getValue))
        // Other ledgers carry no cleared date.
        getAs(secretary, "/api/finance/accounts/$cash/ledger?from=2026-04-01&to=2026-04-30")
            .andExpect(jsonPath("$.lines[0].clearedOn").value(nullValue()))
        getAs(committee, "/api/finance/statements/$april/brs.csv")
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
    }
}
