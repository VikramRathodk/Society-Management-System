package org.devvikram.societymanagement.finance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// Step 5 of finance: the statements on one worked example.
//   Openings 2026-04-01: cash 10,000, bank 50,000, sinking fund 20,000 → General fund 40,000.
//   2026-05-02 receipt: hall rent 5,000 into cash.
//   2026-05-10 transfer: 3,000 cash → bank.
//   2026-06-15 payment: security 12,000 from bank.
//   November bills (default ledger): flat 101 2,625 + flat 102 3,500 = 6,125 maintenance income.
//   Today: flat 101 pays 2,625 by UPI into the bank.
// Expected now: cash 12,000; bank 43,625; member dues 3,500; assets 59,125.
//   Income 11,125, expenditure 12,000, deficit 875; funds 60,000 - 875 = 59,125.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class FinanceStatementsIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var settingsService: SocietySettingsService

    private var societyId = 0L
    private var flatId = 0L
    private var bank = 0L
    private var cash = 0L
    private lateinit var resident: User
    private lateinit var committee: User
    private lateinit var secretary: User

    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))

    private fun user(roleCode: String, flatId: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode user",
            phone = "9${(100000000..999999999).random()}",
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = societyId, flatId = flatId),
        )
    )

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun getAs(user: User, path: String) = mockMvc.perform(get(path).header("Authorization", bearer(user)))

    private fun send(path: String, body: String? = null): ResultActions {
        val builder = post(path).header("Authorization", bearer(secretary))
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun accountId(key: String): Long {
        val json = getAs(secretary, "/api/societies/$societyId/finance/accounts").andReturn().response.contentAsString
        val bySystem = JsonPath.read<List<Number>>(json, "$[?(@.systemKey == '$key')].id")
        return bySystem.ifEmpty { JsonPath.read(json, "$[?(@.code == '$key')].id") }.single().toLong()
    }

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Statements Society").id!!
        val wing = wingService.addWing(societyId, "A")
        flatId = flatService.addFlat(wing.id!!, "101", BigDecimal("850")).id!!
        flatService.addFlat(wing.id!!, "102", BigDecimal("1200"))
        resident = user("RESIDENT", flatId = flatId)
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")

        send("/api/societies/$societyId/maintenance/charge-heads", """{"name":"Maintenance","calcType":"PER_SQFT","amount":2.50}""")
        send("/api/societies/$societyId/maintenance/charge-heads", """{"name":"Sinking fund","calcType":"FIXED","amount":500}""")
        send("/api/societies/$societyId/finance/chart").andExpect(status().isCreated)
        cash = accountId("CASH")
        bank = JsonPath.read<Number>(send("/api/societies/$societyId/finance/bank-accounts",
            """{"name":"HDFC Savings","bankName":"HDFC Bank","accountType":"SAVINGS"}""").andReturn().response.contentAsString, "$.id").toLong()
        send("/api/societies/$societyId/finance/start", """{"startDate":"2026-04-01","openings":[
            {"accountId":$cash,"amount":10000},{"accountId":$bank,"amount":50000},{"accountId":${accountId("3200")},"amount":20000}]}""")
            .andExpect(status().isCreated)
        send("/api/societies/$societyId/finance/receipts",
            """{"date":"2026-05-02","depositAccountId":$cash,"lines":[{"accountId":${accountId("4210")},"amount":5000}]}""").andExpect(status().isCreated)
        send("/api/societies/$societyId/finance/contras",
            """{"date":"2026-05-10","fromAccountId":$cash,"toAccountId":$bank,"amount":3000}""").andExpect(status().isCreated)
        send("/api/societies/$societyId/finance/payments",
            """{"date":"2026-06-15","payingAccountId":$bank,"payee":"Shree Security","lines":[{"accountId":${accountId("5210")},"amount":12000}]}""")
            .andExpect(status().isCreated)
        send("/api/societies/$societyId/maintenance/cycles", """{"periodStart":"2026-11-01","periodEnd":"2026-11-30","dueDate":"2026-11-15"}""")
            .andExpect(status().isCreated)
        send("/api/societies/$societyId/maintenance/payments",
            """{"flatId":$flatId,"amount":2625,"paidOn":"$today","method":"UPI","depositAccountId":$bank}""").andExpect(status().isCreated)
    }

    private val base get() = "/api/societies/$societyId/finance/reports"

    @Test
    fun `the trial balance balances`() {
        getAs(committee, "$base/trial-balance")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanced").value(true))
            .andExpect(jsonPath("$.totalDebit").value(71125)) // cash 12000 + bank 43625 + dues 3500 + security 12000
            .andExpect(jsonPath("$.rows[?(@.accountId == $bank)].debit").value(43625.0))
            .andExpect(jsonPath("$.rows[?(@.accountId == ${accountId("GENERAL_FUND")})].credit").value(40000.0))
        // As of the day before the first entry, only the openings.
        getAs(committee, "$base/trial-balance?asOf=2026-04-30").andExpect(jsonPath("$.totalDebit").value(60000))
    }

    @Test
    fun `income and expenditure shows the period's surplus or deficit by group`() {
        getAs(committee, "$base/income-expenditure?from=2026-04-01")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalIncome").value(11125))
            .andExpect(jsonPath("$.totalExpenditure").value(12000))
            .andExpect(jsonPath("$.surplus").value(-875))
            .andExpect(jsonPath("$.income[?(@.name == 'Maintenance')].total").value(6125.0))
            .andExpect(jsonPath("$.income[?(@.name == 'Other income')].lines[0].name").value("Hall & clubhouse rent"))
            .andExpect(jsonPath("$.expenditure[0].name").value("Services"))
        getAs(committee, "$base/income-expenditure?from=2026-04-01&to=2026-05-31")
            .andExpect(jsonPath("$.surplus").value(5000))
    }

    @Test
    fun `receipts and payments start from the openings and end at the cash and bank balance`() {
        getAs(committee, "$base/receipts-payments?from=2026-04-01")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.openingTotal").value(60000)) // the opening voucher is the opening balance
            .andExpect(jsonPath("$.receiptsTotal").value(7625)) // hall rent 5000 + maintenance 2625
            .andExpect(jsonPath("$.paymentsTotal").value(12000))
            .andExpect(jsonPath("$.closingTotal").value(55625))
            .andExpect(jsonPath("$.receipts[?(@.name == 'Receivables')].lines[0].amount").value(2625.0))
        // A later period opens with what the earlier one closed with; the transfer never shows.
        getAs(committee, "$base/receipts-payments?from=2026-05-05&to=2026-05-31")
            .andExpect(jsonPath("$.openingTotal").value(65000))
            .andExpect(jsonPath("$.receipts", hasSize<Any>(0)))
            .andExpect(jsonPath("$.payments", hasSize<Any>(0)))
            .andExpect(jsonPath("$.closingTotal").value(65000))
    }

    @Test
    fun `the balance sheet balances, with the deficit shown against the funds`() {
        getAs(committee, "$base/balance-sheet")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalAssets").value(59125))
            .andExpect(jsonPath("$.surplus").value(-875))
            .andExpect(jsonPath("$.totalFunds").value(59125))
            .andExpect(jsonPath("$.totalLiabilities").value(0))
            .andExpect(jsonPath("$.balanced").value(true))
            .andExpect(jsonPath("$.assets[?(@.name == 'Cash & bank')].total").value(55625.0))
    }

    @Test
    fun `statements export as CSV for report exporters only`() {
        getAs(committee, "$base/trial-balance/export")
            .andExpect(status().isOk)
            .andExpect(content().string(startsWith("code,account,type,debit,credit")))
            .andExpect(content().string(containsString("Total")))
        getAs(committee, "$base/income-expenditure/export?from=2026-04-01").andExpect(content().string(containsString("Deficit")))
        getAs(committee, "$base/receipts-payments/export?from=2026-04-01").andExpect(content().string(containsString("Opening balance")))
        getAs(committee, "$base/balance-sheet/export").andExpect(content().string(containsString("Surplus / (deficit) to date")))
        getAs(committee, "/api/finance/accounts/$bank/ledger/export?from=2026-04-01").andExpect(content().string(containsString("Closing balance")))
        getAs(committee, "/api/societies/$societyId/finance/day-book/export?from=2026-04-01")
            .andExpect(content().string(containsString("OB/2026-27/0001")))
        getAs(resident, "$base/trial-balance/export").andExpect(status().isForbidden)
        getAs(resident, "$base/balance-sheet").andExpect(status().isForbidden)
    }

    @Test
    fun `residents see the monthly summary only when the society shares it`() {
        getAs(resident, "/api/societies/$societyId/finance/summary").andExpect(status().isForbidden)
        getAs(committee, "/api/societies/$societyId/finance/summary?from=2026-05-01&to=2026-06-30")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.months", hasSize<Any>(2)))
        settingsService.update(societyId, mapOf("residentsSeeFinanceSummary" to true), secretary.id!!)
        getAs(resident, "/api/societies/$societyId/finance/summary?from=2026-05-01&to=2026-06-30")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.months[0].month").value("2026-05"))
            .andExpect(jsonPath("$.months[0].totalIncome").value(5000))
            .andExpect(jsonPath("$.months[0].income[0].name").value("Other income"))
            .andExpect(jsonPath("$.months[0].closingCashAndBank").value(65000))
            .andExpect(jsonPath("$.months[1].totalExpenditure").value(12000))
            .andExpect(jsonPath("$.months[1].expenditure[0].name").value("Services"))
            .andExpect(jsonPath("$.months[1].closingCashAndBank").value(53000))
            // No payee or entry ever reaches a resident.
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Shree Security"))))
    }
}
