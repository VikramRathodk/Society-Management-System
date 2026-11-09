package org.devvikram.societymanagement.finance

import com.jayway.jsonpath.JsonPath
import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
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
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

// Step 1 of finance: chart of accounts, bank accounts, starting the books with openings, ledgers, audit
// log, and the database's own invariants. Real local Postgres, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class FinanceBooksIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var entityManager: EntityManager

    private var societyId = 0L
    private var flatId = 0L
    private lateinit var resident: User
    private lateinit var committee: User
    private lateinit var secretary: User
    private lateinit var admin: User
    private lateinit var outsideSecretary: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, inSociety: Long = societyId, flatId: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = inSociety, flatId = flatId),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Finance Test Society").id!!
        val wing = wingService.addWing(societyId, "A")
        flatId = flatService.addFlat(wing.id!!, "101").id!!
        resident = user("RESIDENT", flatId = flatId)
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")
        admin = user("ADMIN")
        outsideSecretary = user("SECRETARY", inSociety = societyService.createSociety("Other Society").id!!)
    }

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

    private fun seedChart() = sendAs(secretary, "POST", "/api/societies/$societyId/finance/chart").andExpect(status().isCreated)

    // Account id by system key, or by code.
    private fun accountId(key: String): Long {
        val json = body(getAs(secretary, "/api/societies/$societyId/finance/accounts"))
        val bySystem = JsonPath.read<List<Number>>(json, "$[?(@.systemKey == '$key')].id")
        val ids = bySystem.ifEmpty { JsonPath.read(json, "$[?(@.code == '$key')].id") }
        return ids.single().toLong()
    }

    private fun accountField(id: Long, field: String): Any? {
        val json = body(getAs(secretary, "/api/societies/$societyId/finance/accounts"))
        return JsonPath.read<List<Any?>>(json, "$[?(@.id == $id)].$field").single()
    }

    private fun addBank(name: String = "HDFC Savings") = JsonPath.read<Number>(
        body(
            sendAs(secretary, "POST", "/api/societies/$societyId/finance/bank-accounts",
                """{"name":"$name","bankName":"HDFC Bank","accountType":"SAVINGS","last4":"4321","ifsc":"hdfc0001234"}""")
                .andExpect(status().isCreated)
        ),
        "$.id",
    ).toLong()

    // Cash 5,000 + bank 20,000 (assets) against sinking fund 15,000: General fund takes 10,000.
    private fun startBooks(expectedNumber: String = "OB/2026-27/0001"): Long {
        seedChart()
        val bank = addBank()
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/start",
            """{"startDate":"2026-04-01","openings":[
                {"accountId":${accountId("CASH")},"amount":5000},
                {"accountId":$bank,"amount":20000},
                {"accountId":${accountId("3200")},"amount":15000}]}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.started").value(true))
            .andExpect(jsonPath("$.startDate").value("2026-04-01"))
            .andExpect(jsonPath("$.openingVoucherNumber").value(expectedNumber))
            .andExpect(jsonPath("$.openingsEditable").value(true))
        return bank
    }

    @Test
    fun `committee reads the books, only the secretary sets them up, residents and outsiders can't`() {
        getAs(committee, "/api/societies/$societyId/finance/books")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.started").value(false))
            .andExpect(jsonPath("$.hasChart").value(false))

        sendAs(committee, "POST", "/api/societies/$societyId/finance/chart").andExpect(status().isForbidden)
        getAs(resident, "/api/societies/$societyId/finance/accounts").andExpect(status().isForbidden)
        getAs(outsideSecretary, "/api/societies/$societyId/finance/accounts").andExpect(status().isForbidden)

        val seeded = JsonPath.read<Number>(body(seedChart()), "$.accounts").toInt()
        assertTrue(seeded > 40)
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/chart").andExpect(status().isConflict)

        getAs(committee, "/api/societies/$societyId/finance/accounts")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(seeded)))
            .andExpect(jsonPath("$[?(@.parentId == null)]", hasSize<Any>(5)))
            .andExpect(jsonPath("$[?(@.systemKey == 'MEMBER_ADVANCE')].type").value("LIABILITY"))
    }

    @Test
    fun `starting the books posts one balanced opening voucher with the General fund taking the difference`() {
        val bank = startBooks()

        assertEquals(5000.0, (accountField(accountId("CASH"), "balance") as Number).toDouble())
        assertEquals(10000.0, (accountField(accountId("GENERAL_FUND"), "balance") as Number).toDouble())
        // A group shows everything under it: cash + bank.
        assertEquals(25000.0, (accountField(accountId("CASH_AND_BANK"), "balance") as Number).toDouble())
        assertEquals(20000.0, (accountField(bank, "balance") as Number).toDouble())

        getAs(committee, "/api/societies/$societyId/finance/openings")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries", hasSize<Any>(3)))
            .andExpect(jsonPath("$.generalFund").value(10000))
            .andExpect(jsonPath("$.editable").value(true))

        sendAs(secretary, "POST", "/api/societies/$societyId/finance/start", """{"startDate":"2026-04-01"}""")
            .andExpect(status().isConflict)
    }

    @Test
    fun `openings refuse the General fund, member accounts, groups, repeats and future dates`() {
        seedChart()
        fun start(openings: String, date: String = "2026-04-01") =
            sendAs(secretary, "POST", "/api/societies/$societyId/finance/start", """{"startDate":"$date","openings":[$openings]}""")

        start("""{"accountId":${accountId("GENERAL_FUND")},"amount":100}""").andExpect(status().isBadRequest)
        start("""{"accountId":${accountId("MEMBER_DUES")},"amount":100}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("maintenance")))
        start("""{"accountId":${accountId("CASH_AND_BANK")},"amount":100}""").andExpect(status().isBadRequest)
        start("""{"accountId":${accountId("CASH")},"amount":1},{"accountId":${accountId("CASH")},"amount":2}""")
            .andExpect(status().isBadRequest)
        start("", date = "2099-01-01").andExpect(status().isBadRequest)

        getAs(secretary, "/api/societies/$societyId/finance/books").andExpect(jsonPath("$.started").value(false))
    }

    @Test
    fun `negative openings sit on the other side, e g an overdrawn bank`() {
        seedChart()
        val bank = addBank()
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/start",
            """{"startDate":"2026-04-01","openings":[{"accountId":$bank,"amount":-2500}]}""")
            .andExpect(status().isCreated)
        assertEquals(-2500.0, (accountField(bank, "balance") as Number).toDouble())
        assertEquals(-2500.0, (accountField(accountId("GENERAL_FUND"), "balance") as Number).toDouble())
    }

    @Test
    fun `openings can be corrected with a reason while no other voucher exists, and it is audited`() {
        startBooks()
        val cash = accountId("CASH")
        sendAs(secretary, "PUT", "/api/societies/$societyId/finance/openings",
            """{"openings":[{"accountId":$cash,"amount":6000}]}""").andExpect(status().isBadRequest)

        sendAs(secretary, "PUT", "/api/societies/$societyId/finance/openings",
            """{"startDate":"2026-05-01","openings":[{"accountId":$cash,"amount":6000}],"reason":"Cash count was wrong"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startDate").value("2026-05-01"))
            .andExpect(jsonPath("$.voucherNumber").value("OB/2026-27/0001"))
            .andExpect(jsonPath("$.entries", hasSize<Any>(1)))
            .andExpect(jsonPath("$.generalFund").value(6000))

        getAs(committee, "/api/societies/$societyId/finance/audit?entityType=BOOKS")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].action").value("UPDATE"))
            .andExpect(jsonPath("$[0].reason").value("Cash count was wrong"))
            .andExpect(jsonPath("$[0].performedByName").value("SECRETARY user"))
            .andExpect(jsonPath("$[1].action").value("START"))
        getAs(resident, "/api/societies/$societyId/finance/audit").andExpect(status().isForbidden)
    }

    @Test
    fun `the chart keeps its shape - types follow parents, no cycles, groups aren't posted to`() {
        seedChart()
        val utilities = accountId("5100")
        val electricity = accountId("5110")

        val gas = JsonPath.read<Number>(
            body(
                sendAs(secretary, "POST", "/api/societies/$societyId/finance/accounts",
                    """{"code":"5130","name":"Piped gas","parentId":$utilities}""")
                    .andExpect(status().isCreated)
                    .andExpect(jsonPath("$.type").value("EXPENSE"))
                    .andExpect(jsonPath("$.group").value(false))
            ),
            "$.id",
        ).toLong()

        // Under a ledger, a duplicate code, a duplicate name.
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/accounts",
            """{"code":"5131","name":"Cooking gas","parentId":$electricity}""").andExpect(status().isBadRequest)
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/accounts",
            """{"code":"5130","name":"Something","parentId":$utilities}""").andExpect(status().isBadRequest)
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/accounts",
            """{"code":"5139","name":"piped GAS","parentId":$utilities}""").andExpect(status().isBadRequest)

        // Moving an expense under income, or a group under its own child.
        sendAs(secretary, "PATCH", "/api/finance/accounts/$gas", """{"parentId":${accountId("4200")}}""")
            .andExpect(status().isBadRequest)
        val sub = JsonPath.read<Number>(
            body(sendAs(secretary, "POST", "/api/societies/$societyId/finance/accounts",
                """{"code":"5150","name":"Utility sub-group","parentId":$utilities,"group":true}""")), "$.id",
        ).toLong()
        sendAs(secretary, "PATCH", "/api/finance/accounts/$utilities", """{"parentId":$sub}""")
            .andExpect(status().isBadRequest)
        sendAs(secretary, "PATCH", "/api/finance/accounts/$gas", """{"parentId":$sub}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.parentId").value(sub))

        // A group with active accounts under it stays active; a fresh ledger can be deleted.
        sendAs(secretary, "PATCH", "/api/finance/accounts/$sub", """{"active":false}""").andExpect(status().isConflict)
        sendAs(secretary, "PATCH", "/api/finance/accounts/$gas", """{"active":false}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.active").value(false))
        sendAs(secretary, "PATCH", "/api/finance/accounts/$sub", """{"active":false}""").andExpect(status().isOk)
        sendAs(secretary, "DELETE", "/api/finance/accounts/$sub").andExpect(status().isConflict)
        sendAs(secretary, "DELETE", "/api/finance/accounts/$gas").andExpect(status().isNoContent)

        sendAs(committee, "PATCH", "/api/finance/accounts/$electricity", """{"name":"Power"}""").andExpect(status().isForbidden)
    }

    @Test
    fun `system accounts and roots stay, accounts with entries are deactivated instead of deleted`() {
        startBooks()
        val cash = accountId("CASH")
        val assets = accountId("1000")

        sendAs(secretary, "DELETE", "/api/finance/accounts/$cash").andExpect(status().isBadRequest)
        sendAs(secretary, "PATCH", "/api/finance/accounts/$cash", """{"active":false}""").andExpect(status().isBadRequest)
        sendAs(secretary, "PATCH", "/api/finance/accounts/$cash", """{"group":true}""").andExpect(status().isBadRequest)
        sendAs(secretary, "PATCH", "/api/finance/accounts/$assets", """{"parentId":${accountId("1200")}}""")
            .andExpect(status().isBadRequest)
        // Renaming is fine.
        sendAs(secretary, "PATCH", "/api/finance/accounts/$cash", """{"name":"Petty cash box"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.name").value("Petty cash box"))

        val sinking = accountId("3200")
        sendAs(secretary, "DELETE", "/api/finance/accounts/$sinking").andExpect(status().isConflict)
        sendAs(secretary, "PATCH", "/api/finance/accounts/$sinking", """{"group":true}""").andExpect(status().isConflict)
        sendAs(secretary, "PATCH", "/api/finance/accounts/$sinking", """{"active":false}""").andExpect(status().isOk)

        getAs(committee, "/api/societies/$societyId/finance/audit?entityType=ACCOUNT&entityId=$cash")
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].oldValues", containsString("Cash in hand")))
            .andExpect(jsonPath("$[0].newValues", containsString("Petty cash box")))
    }

    @Test
    fun `bank accounts get a code under Bank accounts and keep only the last 4 digits`() {
        seedChart()
        val bank = addBank()
        val second = addBank("SBI Current")
        assertEquals("1121", accountField(bank, "code"))
        assertEquals("1122", accountField(second, "code"))
        assertEquals(accountId("BANK_ACCOUNTS"), (accountField(bank, "parentId") as Number).toLong())

        getAs(committee, "/api/societies/$societyId/finance/accounts")
            .andExpect(jsonPath("$[?(@.id == $bank)].bank.ifsc").value("HDFC0001234"))
            .andExpect(jsonPath("$[?(@.id == $bank)].bank.last4").value("4321"))

        sendAs(secretary, "POST", "/api/societies/$societyId/finance/bank-accounts",
            """{"name":"Bad","bankName":"X","accountType":"SAVINGS","ifsc":"NOTANIFSC"}""").andExpect(status().isBadRequest)
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/bank-accounts",
            """{"name":"Bad","bankName":"X","accountType":"SAVINGS","last4":"12a4"}""").andExpect(status().isBadRequest)

        sendAs(secretary, "PATCH", "/api/finance/bank-accounts/$bank", """{"last4":"","bankName":"HDFC Bank Ltd"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bank.last4").doesNotExist())
            .andExpect(jsonPath("$.bank.bankName").value("HDFC Bank Ltd"))
        sendAs(secretary, "PATCH", "/api/finance/bank-accounts/${accountId("CASH")}", """{"bankName":"X"}""")
            .andExpect(status().isNotFound)
    }

    @Test
    fun `an account ledger shows the opening, each entry with a running balance and the closing`() {
        val bank = startBooks()

        getAs(committee, "/api/finance/accounts/$bank/ledger?from=2026-04-01&to=2026-12-31")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.opening").value(0))
            .andExpect(jsonPath("$.lines", hasSize<Any>(1)))
            .andExpect(jsonPath("$.lines[0].voucherNumber").value("OB/2026-27/0001"))
            .andExpect(jsonPath("$.lines[0].debit").value(20000))
            .andExpect(jsonPath("$.lines[0].balance").value(20000))
            .andExpect(jsonPath("$.closing").value(20000))

        // From a later day the opening voucher is already in the opening balance.
        getAs(committee, "/api/finance/accounts/$bank/ledger?from=2026-05-01&to=2026-12-31")
            .andExpect(jsonPath("$.opening").value(20000))
            .andExpect(jsonPath("$.lines", hasSize<Any>(0)))
            .andExpect(jsonPath("$.closing").value(20000))

        getAs(committee, "/api/finance/accounts/${accountId("CASH_AND_BANK")}/ledger").andExpect(status().isBadRequest)
        getAs(committee, "/api/finance/accounts/$bank/ledger?flatId=$flatId").andExpect(status().isBadRequest)
        getAs(committee, "/api/finance/accounts/${accountId("MEMBER_DUES")}/ledger?flatId=$flatId&from=2026-04-01")
            .andExpect(status().isOk).andExpect(jsonPath("$.flatLabel").value("A-101"))
        getAs(resident, "/api/finance/accounts/$bank/ledger").andExpect(status().isForbidden)
    }

    @Test
    fun `finance settings are audited, and the financial year is fixed once the books start`() {
        sendAs(admin, "PATCH", "/api/societies/$societyId/settings", """{"financeApprovalAbove":5000}""")
            .andExpect(status().isOk)
        getAs(committee, "/api/societies/$societyId/finance/audit?entityType=SETTING")
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].oldValues").value("""{"financeApprovalAbove":"0"}"""))
            .andExpect(jsonPath("$[0].newValues").value("""{"financeApprovalAbove":"5000"}"""))

        // Other settings aren't finance's business.
        sendAs(admin, "PATCH", "/api/societies/$societyId/settings", """{"inviteExpiryDays":3}""").andExpect(status().isOk)
        getAs(committee, "/api/societies/$societyId/finance/audit?entityType=SETTING").andExpect(jsonPath("$", hasSize<Any>(1)))

        sendAs(admin, "PATCH", "/api/societies/$societyId/settings", """{"financialYearStartMonth":1}""").andExpect(status().isOk)
        startBooks(expectedNumber = "OB/2026/0001")
        sendAs(admin, "PATCH", "/api/societies/$societyId/settings", """{"financialYearStartMonth":4}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("financial year")))
    }

    // ---- The database refuses broken books even without the application ----

    private fun rawVoucher(): Long {
        entityManager.flush()
        return jdbc.queryForObject(
            """insert into finance_voucher (society_id, type, financial_year, seq, number, voucher_date, status, amount, created_by)
               values (?, 'JOURNAL', '2026-27', 99, 'JV/TEST/0099', date '2026-04-02', 'POSTED', 100, ?) returning id""",
            Long::class.java, societyId, secretary.id,
        )!!
    }

    @Test
    fun `an unbalanced voucher fails at commit`() {
        startBooks()
        val voucher = rawVoucher()
        jdbc.update("insert into finance_line (voucher_id, account_id, debit) values (?, ?, 100)", voucher, accountId("CASH"))
        val error = assertThrows<Exception> { jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE") }
        assertTrue(error.stackTraceToString().contains("does not balance"))
    }

    @Test
    fun `lines can't post to a group`() {
        startBooks()
        val voucher = rawVoucher()
        val onGroup = assertThrows<Exception> {
            jdbc.update("insert into finance_line (voucher_id, account_id, debit) values (?, ?, 100)", voucher, accountId("CASH_AND_BANK"))
        }
        assertTrue(onGroup.stackTraceToString().contains("is a group"))
    }

    @Test
    fun `member dues lines need a flat`() {
        startBooks()
        val voucher = rawVoucher()
        val noFlat = assertThrows<Exception> {
            jdbc.update("insert into finance_line (voucher_id, account_id, debit) values (?, ?, 100)", voucher, accountId("MEMBER_DUES"))
        }
        assertTrue(noFlat.stackTraceToString().contains("flat is required"))
    }

    @Test
    fun `audit rows can't be changed or deleted`() {
        startBooks()
        entityManager.flush()
        val error = assertThrows<Exception> { jdbc.update("delete from finance_audit_log where society_id = ?", societyId) }
        assertTrue(error.stackTraceToString().contains("insert-only"))
    }

    @Test
    fun `amounts keep paise exactly`() {
        seedChart()
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/start",
            """{"startDate":"2026-04-01","openings":[{"accountId":${accountId("CASH")},"amount":0.10},{"accountId":${accountId("1220")},"amount":0.20}]}""")
            .andExpect(status().isCreated)
        assertEquals(BigDecimal("0.30"), BigDecimal(accountField(accountId("GENERAL_FUND"), "balance").toString()).setScale(2))
    }
}
