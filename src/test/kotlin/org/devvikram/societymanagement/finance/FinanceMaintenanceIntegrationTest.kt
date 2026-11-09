package org.devvikram.societymanagement.finance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.finance.service.MaintenancePostings
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.maintenance.service.MaintenanceBillsIssued
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// Step 4 of finance: maintenance posts to the books on its own, once per event, and the maintenance check
// stays clean. Flat 101 (850 sq ft) is billed 2,125 maintenance + 500 sinking fund = 2,625 per cycle;
// flat 102 (1,200 sq ft) 3,000 + 500 = 3,500. Real local Postgres, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class FinanceMaintenanceIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var settingsService: SocietySettingsService
    @Autowired lateinit var postings: MaintenancePostings

    private var societyId = 0L
    private var flatId = 0L
    private var otherFlatId = 0L
    private var bank = 0L
    private lateinit var resident: User
    private lateinit var committee: User
    private lateinit var secretary: User

    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
    private fun period(start: String, end: String, due: String) = """{"periodStart":"$start","periodEnd":"$end","dueDate":"$due"}"""
    private val november = period("2026-11-01", "2026-11-30", "2026-11-15")
    private val december = period("2026-12-01", "2026-12-31", "2026-12-16")

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, flatId: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = societyId, flatId = flatId),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Books Meet Maintenance Society").id!!
        val wing = wingService.addWing(societyId, "A")
        flatId = flatService.addFlat(wing.id!!, "101", BigDecimal("850")).id!!
        otherFlatId = flatService.addFlat(wing.id!!, "102", BigDecimal("1200")).id!!
        resident = user("RESIDENT", flatId = flatId)
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")
        send("POST", "/api/societies/$societyId/maintenance/charge-heads", """{"name":"Maintenance","calcType":"PER_SQFT","amount":2.50}""")
            .andExpect(status().isCreated)
        send("POST", "/api/societies/$societyId/maintenance/charge-heads", """{"name":"Sinking fund","calcType":"FIXED","amount":500}""")
            .andExpect(status().isCreated)
        send("POST", "/api/societies/$societyId/finance/chart").andExpect(status().isCreated)
        bank = JsonPath.read<Number>(body(send("POST", "/api/societies/$societyId/finance/bank-accounts",
            """{"name":"HDFC Savings","bankName":"HDFC Bank","accountType":"SAVINGS"}""")), "$.id").toLong()
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun getAs(user: User, path: String) = mockMvc.perform(get(path).header("Authorization", bearer(user)))

    private fun send(method: String, path: String, body: String? = null, by: User = secretary): ResultActions {
        val builder = when (method) {
            "PUT" -> put(path)
            "PATCH" -> patch(path)
            else -> post(path)
        }.header("Authorization", bearer(by))
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun body(result: ResultActions) = result.andReturn().response.contentAsString

    private fun read(result: ResultActions, path: String): Long = JsonPath.read<Number>(body(result), path).toLong()

    private fun startBooks(cash: Int = 10000) =
        send("POST", "/api/societies/$societyId/finance/start",
            """{"startDate":"2026-04-01","openings":[{"accountId":${accountId("CASH")},"amount":$cash}]}""")
            .andExpect(status().isCreated)

    private fun accountId(key: String): Long {
        val json = body(getAs(secretary, "/api/societies/$societyId/finance/accounts"))
        val bySystem = JsonPath.read<List<Number>>(json, "$[?(@.systemKey == '$key')].id")
        return bySystem.ifEmpty { JsonPath.read(json, "$[?(@.code == '$key')].id") }.single().toLong()
    }

    private fun balance(id: Long): BigDecimal {
        val json = body(getAs(secretary, "/api/societies/$societyId/finance/accounts"))
        return BigDecimal(JsonPath.read<List<Any>>(json, "$[?(@.id == $id)].balance").single().toString()).setScale(2)
    }

    private fun issue(p: String = november): Long =
        read(send("POST", "/api/societies/$societyId/maintenance/cycles", p).andExpect(status().isCreated), "$.id")

    private fun billOf(cycleId: Long, flat: Long = flatId): Long =
        read(getAs(secretary, "/api/maintenance/bills?societyId=$societyId&cycleId=$cycleId&flatId=$flat"), "$[0].id")

    private fun pay(amount: String, flat: Long = flatId, method: String = "UPI", extra: String = "", paidOn: LocalDate = today) =
        send("POST", "/api/societies/$societyId/maintenance/payments",
            """{"flatId":$flat,"amount":$amount,"paidOn":"$paidOn","method":"$method"$extra}""")

    // SYSTEM vouchers, newest first, as parsed JSON.
    private fun systemVouchers(): List<Map<String, Any?>> =
        JsonPath.read(body(getAs(committee, "/api/societies/$societyId/finance/vouchers?type=SYSTEM&size=200")), "$")

    private fun line(voucher: Map<String, Any?>, accountId: Long, flat: Long? = null): Pair<BigDecimal, BigDecimal> {
        @Suppress("UNCHECKED_CAST")
        val lines = (voucher["lines"] as List<Map<String, Any?>>)
            .filter { (it["accountId"] as Number).toLong() == accountId && (flat == null || (it["flatId"] as Number?)?.toLong() == flat) }
        fun sum(key: String) = lines.fold(BigDecimal.ZERO) { s, l -> s + BigDecimal(l[key].toString()) }.setScale(2)
        return sum("debit") to sum("credit")
    }

    private fun d(value: String) = BigDecimal(value).setScale(2)

    // The member sub-ledgers agree with maintenance, flat by flat.
    private fun expectCheckOk() {
        getAs(committee, "/api/societies/$societyId/finance/maintenance-check")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mismatches", hasSize<Any>(0)))
            .andExpect(jsonPath("$.ok").value(true))
    }

    private fun mapSinkingFund() {
        val heads = body(getAs(secretary, "/api/societies/$societyId/finance/charge-head-accounts"))
        val sinkingHead = JsonPath.read<List<Number>>(heads, "$[?(@.chargeHeadName == 'Sinking fund')].chargeHeadId").single()
        send("PUT", "/api/societies/$societyId/finance/charge-head-accounts",
            """{"mappings":[{"chargeHeadId":$sinkingHead,"accountId":${accountId("3200")}}]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.chargeHeadName == 'Sinking fund')].mapped").value(true))
            .andExpect(jsonPath("$[?(@.chargeHeadName == 'Maintenance')].mapped").value(false))
            .andExpect(jsonPath("$[?(@.chargeHeadName == 'Maintenance')].accountName").value("Maintenance charges"))
    }

    @Test
    fun `issuing bills books member dues against each head's ledger`() {
        startBooks()
        mapSinkingFund()
        issue()
        val voucher = systemVouchers().single()
        assertTrue((voucher["number"] as String).startsWith("SV/2026-27/"))
        assertEquals("MAINTENANCE_CYCLE", voucher["sourceType"])
        assertEquals(d("2625") to d("0"), line(voucher, accountId("MEMBER_DUES"), flatId))
        assertEquals(d("3500") to d("0"), line(voucher, accountId("MEMBER_DUES"), otherFlatId))
        assertEquals(d("0") to d("5125"), line(voucher, accountId("MAINTENANCE_INCOME")))
        assertEquals(d("0") to d("1000"), line(voucher, accountId("3200")))
        assertEquals("2026-11-01", voucher["date"])
        expectCheckOk()
    }

    @Test
    fun `a payment goes into its account, pays the dues and keeps the rest as an advance`() {
        startBooks()
        issue()
        pay("5000", extra = ""","depositAccountId":$bank""").andExpect(status().isCreated)
        assertEquals(d("5000"), balance(bank))
        assertEquals(d("2375"), balance(accountId("MEMBER_ADVANCE")))
        assertEquals(d("3500"), balance(accountId("MEMBER_DUES")))
        expectCheckOk()

        // No account named: the method's default, else cash.
        pay("100", flat = otherFlatId, method = "CASH").andExpect(status().isCreated)
        assertEquals(d("10100"), balance(accountId("CASH")))
        send("PUT", "/api/societies/$societyId/finance/defaults", """{"cashDepositAccountId":${accountId("CASH")},"upiDepositAccountId":$bank}""")
            .andExpect(status().isOk)
        pay("200", flat = otherFlatId, method = "UPI").andExpect(status().isCreated)
        assertEquals(d("5200"), balance(bank))

        // A named account must be a cash or bank ledger of this society.
        pay("1", extra = ""","depositAccountId":${accountId("5110")}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.message", containsString("not a cash or bank account")))
    }

    @Test
    fun `credit spent at issue moves from advances to dues, and a void undoes the payment and its credit`() {
        startBooks()
        issue()
        val payment = read(pay("5000", extra = ""","depositAccountId":$bank"""), "$.id")
        issue(december)
        val applied = systemVouchers().first { it["narration"].toString().startsWith("Advance maintenance used") }
        assertEquals(d("2375") to d("0"), line(applied, accountId("MEMBER_ADVANCE"), flatId))
        assertEquals(d("0") to d("2375"), line(applied, accountId("MEMBER_DUES"), flatId))
        assertEquals(d("0"), balance(accountId("MEMBER_ADVANCE")))
        expectCheckOk()

        send("POST", "/api/maintenance/payments/$payment/void", """{"reason":"Bounced"}""").andExpect(status().isOk)
        assertEquals(d("0"), balance(bank))
        assertEquals(d("0"), balance(accountId("MEMBER_ADVANCE")))
        // Both cycles' bills for both flats are owed again.
        assertEquals(d("12250"), balance(accountId("MEMBER_DUES")))
        expectCheckOk()
    }

    @Test
    fun `a waiver is written off, and cancelling a cycle undoes its bills, waivers and credit use`() {
        startBooks()
        pay("3000")
        val nov = issue()
        send("POST", "/api/maintenance/bills/${billOf(nov, otherFlatId)}/waive", """{"reason":"Hardship"}""").andExpect(status().isOk)
        assertEquals(d("3500"), balance(accountId("WRITE_OFF")))
        expectCheckOk()

        send("POST", "/api/maintenance/cycles/$nov/cancel", """{"reason":"Wrong rates"}""").andExpect(status().isOk)
        assertEquals(d("0"), balance(accountId("WRITE_OFF")))
        assertEquals(d("0"), balance(accountId("MAINTENANCE_INCOME")))
        assertEquals(d("0"), balance(accountId("MEMBER_DUES")))
        assertEquals(d("3000"), balance(accountId("MEMBER_ADVANCE")))
        expectCheckOk()
    }

    @Test
    fun `refunds take the advance down and the money out - a void puts both back`() {
        startBooks()
        send("PUT", "/api/societies/$societyId/finance/defaults", """{"defaultPayingAccountId":$bank,"cashDepositAccountId":${accountId("CASH")}}""")
        pay("3000", extra = ""","depositAccountId":$bank""")
        val refund = read(send("POST", "/api/societies/$societyId/maintenance/refunds",
            """{"flatId":$flatId,"amount":1000,"paidOn":"$today","method":"BANK_TRANSFER","reason":"Moved out"}""")
            .andExpect(status().isCreated), "$.id")
        assertEquals(d("2000"), balance(bank))
        assertEquals(d("2000"), balance(accountId("MEMBER_ADVANCE")))
        expectCheckOk()

        send("POST", "/api/maintenance/refunds/$refund/void", """{"reason":"Transfer failed"}""").andExpect(status().isOk)
        assertEquals(d("3000"), balance(bank))
        assertEquals(d("3000"), balance(accountId("MEMBER_ADVANCE")))
        expectCheckOk()
    }

    @Test
    fun `a waiting refund isn't in the books until it is approved`() {
        startBooks()
        settingsService.update(societyId, mapOf("financeApprovalAbove" to 500), secretary.id!!)
        pay("3000")
        val refund = read(send("POST", "/api/societies/$societyId/maintenance/refunds",
            """{"flatId":$flatId,"amount":1000,"paidOn":"$today","method":"CASH","reason":"Moved out"}"""), "$.id")
        assertEquals(d("3000"), balance(accountId("MEMBER_ADVANCE")))
        expectCheckOk() // the waiting refund still counts as advance on both sides

        send("POST", "/api/maintenance/refunds/$refund/approve", by = committee).andExpect(status().isOk)
        assertEquals(d("2000"), balance(accountId("MEMBER_ADVANCE")))
        assertEquals(d("12000"), balance(accountId("CASH")))
        expectCheckOk()
    }

    @Test
    fun `starting the books later takes over each flat's dues and credit from maintenance`() {
        val nov = issue()
        pay("5000") // flat 101: 2625 paid, 2375 credit
        send("POST", "/api/maintenance/bills/${billOf(nov, otherFlatId)}/waive", """{"reason":"Hardship"}""")
        assertTrue(systemVouchers().isEmpty()) // no books yet, nothing posted

        startBooks()
        getAs(committee, "/api/societies/$societyId/finance/openings")
            .andExpect(jsonPath("$.memberDues").value(0))
            .andExpect(jsonPath("$.memberAdvances").value(2375))
            .andExpect(jsonPath("$.generalFund").value(7625)) // 10000 cash - 2375 owed to the member
        expectCheckOk()

        // Undoing what happened before the books started still leaves them in agreement.
        send("POST", "/api/maintenance/cycles/$nov/cancel", """{"reason":"Rates were wrong"}""").andExpect(status().isConflict)
        val payment = read(getAs(secretary, "/api/maintenance/payments?societyId=$societyId"), "$[0].id")
        send("POST", "/api/maintenance/payments/$payment/void", """{"reason":"Entered twice"}""").andExpect(status().isOk)
        expectCheckOk()
        send("POST", "/api/maintenance/cycles/$nov/cancel", """{"reason":"Rates were wrong"}""").andExpect(status().isOk)
        expectCheckOk()
        assertEquals(d("0"), balance(accountId("MEMBER_DUES")))
    }

    @Test
    fun `opening balances entered in maintenance after the books start are booked against the General fund`() {
        startBooks()
        send("POST", "/api/societies/$societyId/maintenance/opening-balances",
            """{"asOf":"2026-09-30","dueDate":"2026-10-15","entries":[{"flatId":$flatId,"amount":4000}]}""")
            .andExpect(status().isCreated)
        assertEquals(d("4000"), balance(accountId("MEMBER_DUES")))
        // Old dues are an asset the society already had: the fund grows by them (10000 cash + 4000).
        assertEquals(d("14000"), balance(accountId("GENERAL_FUND")))
        assertEquals(d("0"), balance(accountId("MAINTENANCE_INCOME")))
        expectCheckOk()
    }

    @Test
    fun `postings dated inside the locked period are booked on the first open day`() {
        startBooks()
        issue()
        send("PUT", "/api/societies/$societyId/finance/lock", """{"lockedUntil":"${today.minusDays(2)}"}""").andExpect(status().isOk)
        pay("1000", paidOn = today.minusDays(5)).andExpect(status().isCreated)
        val receipt = systemVouchers().first { it["narration"].toString().startsWith("Maintenance receipt") }
        assertEquals(today.minusDays(1).toString(), receipt["date"])
        assertTrue(receipt["narration"].toString().contains("the books are closed for ${today.minusDays(5)}"))
        expectCheckOk()
    }

    @Test
    fun `the same event posts once`() {
        startBooks()
        val cycleId = issue()
        val before = systemVouchers().size
        postings.onIssued(MaintenanceBillsIssued(cycleId, societyId, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"),
            LocalDate.parse("2026-11-15"), emptyList()))
        assertEquals(before, systemVouchers().size)
        expectCheckOk()
    }

    @Test
    fun `system entries can't be voided by hand, and only report readers see the check`() {
        startBooks()
        issue()
        val voucher = (systemVouchers().single()["id"] as Number).toLong()
        send("POST", "/api/finance/vouchers/$voucher/void", """{"reason":"x"}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.message", containsString("maintenance")))
        getAs(resident, "/api/societies/$societyId/finance/maintenance-check").andExpect(status().isForbidden)
        getAs(resident, "/api/societies/$societyId/finance/charge-head-accounts").andExpect(status().isForbidden)
    }

    @Test
    fun `charge heads map only to income, fund or liability ledgers`() {
        startBooks()
        val heads = body(getAs(secretary, "/api/societies/$societyId/finance/charge-head-accounts"))
        val head = JsonPath.read<List<Number>>(heads, "$[?(@.chargeHeadName == 'Maintenance')].chargeHeadId").single()
        fun map(account: Long?) = send("PUT", "/api/societies/$societyId/finance/charge-head-accounts",
            """{"mappings":[{"chargeHeadId":$head,"accountId":$account}]}""")
        map(accountId("5110")).andExpect(status().isBadRequest)
        map(bank).andExpect(status().isBadRequest)
        map(accountId("MEMBER_ADVANCE")).andExpect(status().isBadRequest)
        map(accountId("4240")).andExpect(status().isOk)
        map(null).andExpect(jsonPath("$[?(@.chargeHeadName == 'Maintenance')].mapped").value(false))
        getAs(committee, "/api/societies/$societyId/finance/audit?entityType=MAPPING").andExpect(jsonPath("$", hasSize<Any>(2)))
    }
}
