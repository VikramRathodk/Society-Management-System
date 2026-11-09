package org.devvikram.societymanagement.finance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.finance.service.FinanceNotificationListener
import org.devvikram.societymanagement.finance.service.VoucherDecided
import org.devvikram.societymanagement.finance.service.VoucherNeedsApproval
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.maintenance.RecordingPushNotifier
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UpdateProfileCommand
import org.devvikram.societymanagement.user.service.UserService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

// Step 2 of finance: receipts, payments, contras and journals; approval; voiding by reversal; the lock
// date; documents; the day book and cash & bank summary. Books start 2026-04-01 with cash 10,000 and
// bank 50,000. Real local Postgres, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class FinanceVoucherIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var notificationListener: FinanceNotificationListener
    @Autowired lateinit var pushNotifier: RecordingPushNotifier

    private var societyId = 0L
    private var flatId = 0L
    private var cash = 0L
    private var bank = 0L
    private lateinit var resident: User
    private lateinit var committee: User
    private lateinit var secretary: User
    private lateinit var secondSecretary: User
    private lateinit var admin: User

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
        societyId = societyService.createSociety("Voucher Test Society").id!!
        flatId = flatService.addFlat(wingService.addWing(societyId, "A").id!!, "101").id!!
        resident = user("RESIDENT", flatId = flatId)
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")
        secondSecretary = user("SECRETARY")
        admin = user("ADMIN")

        sendAs(secretary, "POST", "/api/societies/$societyId/finance/chart").andExpect(status().isCreated)
        cash = accountId("CASH")
        bank = JsonPath.read<Number>(
            body(sendAs(secretary, "POST", "/api/societies/$societyId/finance/bank-accounts",
                """{"name":"HDFC Savings","bankName":"HDFC Bank","accountType":"SAVINGS"}""")), "$.id",
        ).toLong()
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/start",
            """{"startDate":"2026-04-01","openings":[{"accountId":$cash,"amount":10000},{"accountId":$bank,"amount":50000}]}""")
            .andExpect(status().isCreated)
        pushNotifier.sent.clear()
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

    private fun idOf(result: ResultActions): Long = JsonPath.read<Number>(body(result), "$.id").toLong()

    private fun accountId(key: String): Long {
        val json = body(getAs(secretary, "/api/societies/$societyId/finance/accounts"))
        val bySystem = JsonPath.read<List<Number>>(json, "$[?(@.systemKey == '$key')].id")
        return bySystem.ifEmpty { JsonPath.read(json, "$[?(@.code == '$key')].id") }.single().toLong()
    }

    private fun balance(id: Long): BigDecimal {
        val json = body(getAs(secretary, "/api/societies/$societyId/finance/accounts"))
        return BigDecimal(JsonPath.read<List<Any>>(json, "$[?(@.id == $id)].balance").single().toString()).setScale(2)
    }

    private fun setApprovalAbove(rupees: Int) =
        sendAs(admin, "PATCH", "/api/societies/$societyId/settings", """{"financeApprovalAbove":$rupees}""").andExpect(status().isOk)

    private fun payment(amount: String, by: User = secretary, date: String = "2026-06-15", payee: String = "Shree Security Services") =
        sendAs(by, "POST", "/api/societies/$societyId/finance/payments",
            """{"date":"$date","payingAccountId":$bank,"payee":"$payee","reference":"NEFT 1234",
                "lines":[{"accountId":${accountId("5210")},"amount":$amount}]}""")

    private fun d(value: String) = BigDecimal(value).setScale(2)

    @Test
    fun `a receipt debits the cash or bank ledger and credits the income`() {
        val hallRent = accountId("4210")
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/receipts",
            """{"date":"2026-05-02","depositAccountId":$cash,"payee":"Tony Stark","flatId":$flatId,"narration":"Hall booking 1 May",
                "lines":[{"accountId":$hallRent,"amount":5000}]}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.number").value("RV/2026-27/0001"))
            .andExpect(jsonPath("$.status").value("POSTED"))
            .andExpect(jsonPath("$.amount").value(5000))
            .andExpect(jsonPath("$.flatLabel").value("A-101"))
            .andExpect(jsonPath("$.createdByName").value("SECRETARY user"))
            .andExpect(jsonPath("$.lines", hasSize<Any>(2)))
            .andExpect(jsonPath("$.lines[0].accountName").value("Cash in hand"))
            .andExpect(jsonPath("$.lines[0].debit").value(5000))
            .andExpect(jsonPath("$.lines[1].credit").value(5000))
        assertEquals(d("15000"), balance(cash))
        assertEquals(d("5000"), balance(hallRent))

        fun receipt(deposit: Long, line: Long) = sendAs(secretary, "POST", "/api/societies/$societyId/finance/receipts",
            """{"date":"2026-05-02","depositAccountId":$deposit,"lines":[{"accountId":$line,"amount":100}]}""")
        receipt(accountId("5110"), hallRent).andExpect(status().isBadRequest) // into an expense, not cash/bank
        receipt(cash, bank).andExpect(status().isBadRequest).andExpect(jsonPath("$.message", containsString("transfer")))
        receipt(cash, accountId("MEMBER_DUES")).andExpect(status().isBadRequest).andExpect(jsonPath("$.message", containsString("maintenance")))
        receipt(cash, accountId("4200")).andExpect(status().isBadRequest) // a group
        sendAs(committee, "POST", "/api/societies/$societyId/finance/receipts",
            """{"date":"2026-05-02","depositAccountId":$cash,"lines":[{"accountId":$hallRent,"amount":100}]}""")
            .andExpect(status().isForbidden)
        getAs(resident, "/api/societies/$societyId/finance/vouchers").andExpect(status().isForbidden)
    }

    @Test
    fun `entries can't be dated before the books start or in the future`() {
        payment("100", date = "2026-03-31").andExpect(status().isBadRequest).andExpect(jsonPath("$.message", containsString("start")))
        payment("100", date = "2099-01-01").andExpect(status().isBadRequest).andExpect(jsonPath("$.message", containsString("future")))
        // On the start day is fine.
        payment("100", date = "2026-04-01").andExpect(status().isCreated)
    }

    @Test
    fun `without a threshold a payment posts at once - above one it waits for someone else`() {
        payment("12000").andExpect(status().isCreated).andExpect(jsonPath("$.status").value("POSTED"))
        assertEquals(d("38000"), balance(bank))

        setApprovalAbove(10000)
        payment("10000").andExpect(jsonPath("$.status").value("POSTED")) // at the limit is fine
        val waiting = idOf(payment("25000").andExpect(status().isCreated).andExpect(jsonPath("$.status").value("PENDING_APPROVAL")))
        assertEquals(d("28000"), balance(bank)) // waiting money isn't in the books

        // The maker can't approve their own entry, even holding the permission through a higher role.
        sendAs(secretary, "POST", "/api/finance/vouchers/$waiting/approve").andExpect(status().isForbidden)
        sendAs(resident, "POST", "/api/finance/vouchers/$waiting/approve").andExpect(status().isForbidden)
        sendAs(committee, "POST", "/api/finance/vouchers/$waiting/approve")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("POSTED"))
            .andExpect(jsonPath("$.decidedByName").value("COMMITTEE user"))
        assertEquals(d("3000"), balance(bank))
        sendAs(committee, "POST", "/api/finance/vouchers/$waiting/approve").andExpect(status().isConflict)

        getAs(committee, "/api/societies/$societyId/finance/audit?entityType=VOUCHER&entityId=$waiting")
            .andExpect(jsonPath("$[0].action").value("APPROVE"))
            .andExpect(jsonPath("$[1].action").value("CREATE"))
    }

    @Test
    fun `journals above the threshold wait too - receipts and transfers never do`() {
        setApprovalAbove(1000)
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/contras",
            """{"date":"2026-06-01","fromAccountId":$bank,"toAccountId":$cash,"amount":5000}""")
            .andExpect(jsonPath("$.status").value("POSTED"))
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/receipts",
            """{"date":"2026-06-01","depositAccountId":$cash,"lines":[{"accountId":${accountId("4230")},"amount":5000}]}""")
            .andExpect(jsonPath("$.status").value("POSTED"))
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/journals",
            """{"date":"2026-06-01","narration":"Move FD interest to the sinking fund","lines":[
                {"accountId":${accountId("4220")},"debit":2000},{"accountId":${accountId("3200")},"credit":2000}]}""")
            .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
            .andExpect(jsonPath("$.number").value("JV/2026-27/0001"))
    }

    @Test
    fun `rejecting needs a reason - the maker can edit or withdraw while it waits, nobody else can`() {
        setApprovalAbove(10000)
        val first = idOf(payment("20000"))
        sendAs(committee, "POST", "/api/finance/vouchers/$first/reject", """{}""").andExpect(status().isBadRequest)
        sendAs(secretary, "POST", "/api/finance/vouchers/$first/reject", """{"reason":"mine"}""").andExpect(status().isForbidden)
        sendAs(committee, "POST", "/api/finance/vouchers/$first/reject", """{"reason":"No quote attached"}""")
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.reason").value("No quote attached"))
        sendAs(committee, "POST", "/api/finance/vouchers/$first/approve").andExpect(status().isConflict)

        val second = idOf(payment("20000"))
        val edit = """{"date":"2026-06-20","payingAccountId":$cash,"payee":"Shree Security","lines":[{"accountId":${accountId("5210")},"amount":18000}]}"""
        sendAs(secondSecretary, "PUT", "/api/finance/payments/$second", edit).andExpect(status().isForbidden)
        sendAs(secretary, "PUT", "/api/finance/payments/$second", edit)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.amount").value(18000))
            .andExpect(jsonPath("$.date").value("2026-06-20"))
            .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
        // A different financial year would need a different number.
        sendAs(secretary, "PUT", "/api/finance/payments/$second", edit.replace("2026-06-20", "2026-03-31"))
            .andExpect(status().isBadRequest)

        sendAs(secondSecretary, "POST", "/api/finance/vouchers/$second/withdraw").andExpect(status().isForbidden)
        sendAs(secretary, "POST", "/api/finance/vouchers/$second/withdraw", """{"reason":"Duplicate"}""")
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.reason").value("Withdrawn by the maker: Duplicate"))
        sendAs(secretary, "PUT", "/api/finance/payments/$second", edit).andExpect(status().isConflict)

        // Both kept their numbers; the next one doesn't reuse them.
        payment("100").andExpect(jsonPath("$.number").value("PV/2026-27/0003"))
        assertEquals(d("50000"), balance(bank).add(d("100")))
    }

    @Test
    fun `approvers get a push, the maker doesn't - the maker hears the decision`() {
        userService.updateProfile(committee.id!!, UpdateProfileCommand(fcmToken = "committee-token"))
        userService.updateProfile(secretary.id!!, UpdateProfileCommand(fcmToken = "secretary-token"))
        userService.updateProfile(resident.id!!, UpdateProfileCommand(fcmToken = "resident-token"))

        notificationListener.onNeedsApproval(
            VoucherNeedsApproval(1, societyId, "PV/2026-27/0007", BigDecimal("25000.00"), "Lift Co", secretary.id!!, "SECRETARY user")
        )
        val push = pushNotifier.sent.single()
        assertTrue("committee-token" in push.tokens)
        assertFalse("secretary-token" in push.tokens)
        assertFalse("resident-token" in push.tokens)
        assertEquals("SECRETARY user recorded PV/2026-27/0007: ₹25000.00 to Lift Co", push.body)

        notificationListener.onDecided(VoucherDecided(1, secretary.id!!, "PV/2026-27/0007", approved = false, reason = "Too high"))
        assertEquals(listOf("secretary-token"), pushNotifier.sent.last().tokens.toList())
        assertEquals("PV/2026-27/0007 was rejected: Too high", pushNotifier.sent.last().body)
    }

    @Test
    fun `voiding books a linked reversal dated today and leaves the original in the books`() {
        val paid = idOf(payment("12000"))
        sendAs(secretary, "POST", "/api/finance/vouchers/$paid/void", """{}""").andExpect(status().isBadRequest)
        sendAs(committee, "POST", "/api/finance/vouchers/$paid/void", """{"reason":"x"}""").andExpect(status().isForbidden)

        val voided = body(sendAs(secretary, "POST", "/api/finance/vouchers/$paid/void", """{"reason":"Paid twice"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("VOID"))
            .andExpect(jsonPath("$.reason").value("Paid twice"))
            .andExpect(jsonPath("$.reversedByNumber").value(containsString("PV/")))
            .andExpect(jsonPath("$.voidedByName").value("SECRETARY user")))
        assertEquals(d("50000"), balance(bank))

        val reversalId = JsonPath.read<Number>(voided, "$.reversedByVoucherId").toLong()
        getAs(committee, "/api/finance/vouchers/$reversalId")
            .andExpect(jsonPath("$.reversesVoucherId").value(paid))
            .andExpect(jsonPath("$.status").value("POSTED"))
            .andExpect(jsonPath("$.narration").value(containsString("Paid twice")))
            .andExpect(jsonPath("$.lines[0].credit").value(12000))

        sendAs(secretary, "POST", "/api/finance/vouchers/$paid/void", """{"reason":"again"}""").andExpect(status().isConflict)
        sendAs(secretary, "POST", "/api/finance/vouchers/$reversalId/void", """{"reason":"undo"}""").andExpect(status().isConflict)
        val opening = JsonPath.read<Number>(body(getAs(secretary, "/api/societies/$societyId/finance/books")), "$.openingVoucherId").toLong()
        sendAs(secretary, "POST", "/api/finance/vouchers/$opening/void", """{"reason":"x"}""").andExpect(status().isBadRequest)

        // The ledger keeps both, so the June balance stays as it was reported.
        getAs(committee, "/api/finance/accounts/$bank/ledger?from=2026-04-01&to=2026-06-30")
            .andExpect(jsonPath("$.closing").value(38000))
    }

    @Test
    fun `an entry can be voided even after its account was deactivated`() {
        val water = accountId("5120")
        val paid = idOf(sendAs(secretary, "POST", "/api/societies/$societyId/finance/payments",
            """{"date":"2026-05-10","payingAccountId":$cash,"payee":"Tanker","lines":[{"accountId":$water,"amount":1500}]}"""))
        sendAs(secretary, "PATCH", "/api/finance/accounts/$water", """{"active":false}""").andExpect(status().isOk)
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/payments",
            """{"date":"2026-05-10","payingAccountId":$cash,"payee":"Tanker","lines":[{"accountId":$water,"amount":1}]}""")
            .andExpect(status().isBadRequest)
        sendAs(secretary, "POST", "/api/finance/vouchers/$paid/void", """{"reason":"Wrong society"}""").andExpect(status().isOk)
        assertEquals(d("0"), balance(water))
    }

    @Test
    fun `transfers move money between cash and bank only`() {
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/contras",
            """{"date":"2026-05-05","fromAccountId":$cash,"toAccountId":$bank,"amount":4000,"narration":"Cash deposited"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.number").value("CV/2026-27/0001"))
        assertEquals(d("6000"), balance(cash))
        assertEquals(d("54000"), balance(bank))

        sendAs(secretary, "POST", "/api/societies/$societyId/finance/contras",
            """{"date":"2026-05-05","fromAccountId":$cash,"toAccountId":$cash,"amount":1}""").andExpect(status().isBadRequest)
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/contras",
            """{"date":"2026-05-05","fromAccountId":$cash,"toAccountId":${accountId("5110")},"amount":1}""").andExpect(status().isBadRequest)
    }

    @Test
    fun `a journal must balance, have two lines, a narration, and leave member accounts alone`() {
        fun journal(lines: String, narration: String = "Adjustment") = sendAs(secretary, "POST", "/api/societies/$societyId/finance/journals",
            """{"date":"2026-05-31","narration":"$narration","lines":[$lines]}""")
        val interest = accountId("4220")
        val sinking = accountId("3200")
        journal("""{"accountId":$interest,"debit":100},{"accountId":$sinking,"credit":90}""").andExpect(status().isBadRequest)
        journal("""{"accountId":$interest,"debit":100}""").andExpect(status().isBadRequest)
        journal("""{"accountId":$interest,"debit":100,"credit":100},{"accountId":$sinking,"credit":100}""").andExpect(status().isBadRequest)
        journal("""{"accountId":${accountId("MEMBER_DUES")},"debit":100},{"accountId":$sinking,"credit":100}""").andExpect(status().isBadRequest)
        journal("""{"accountId":$interest,"debit":100},{"accountId":$sinking,"credit":100}""", narration = " ").andExpect(status().isBadRequest)
        journal("""{"accountId":$interest,"debit":100},{"accountId":$sinking,"credit":100}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.amount").value(100))
        assertEquals(d("100"), balance(sinking))
    }

    @Test
    fun `the lock date closes a period - reopening it needs a reason`() {
        sendAs(secretary, "PUT", "/api/societies/$societyId/finance/lock", """{"lockedUntil":"2099-01-01"}""").andExpect(status().isBadRequest)
        sendAs(secretary, "PUT", "/api/societies/$societyId/finance/lock", """{"lockedUntil":"2026-03-01"}""").andExpect(status().isBadRequest)
        setApprovalAbove(10000)
        val waiting = idOf(payment("20000", date = "2026-06-10"))

        sendAs(secretary, "PUT", "/api/societies/$societyId/finance/lock", """{"lockedUntil":"2026-06-30"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.lockedUntil").value("2026-06-30"))
        payment("100", date = "2026-06-30").andExpect(status().isBadRequest).andExpect(jsonPath("$.message", containsString("locked")))
        payment("100", date = "2026-07-01").andExpect(status().isCreated)
        // A waiting entry dated inside the locked period can't be approved any more.
        sendAs(committee, "POST", "/api/finance/vouchers/$waiting/approve").andExpect(status().isBadRequest)

        sendAs(secretary, "PUT", "/api/societies/$societyId/finance/lock", """{"lockedUntil":"2026-05-31"}""").andExpect(status().isBadRequest)
        sendAs(secretary, "PUT", "/api/societies/$societyId/finance/lock", """{"lockedUntil":"2026-05-31","reason":"Auditor found a missed bill"}""")
            .andExpect(status().isOk)
        sendAs(committee, "POST", "/api/finance/vouchers/$waiting/approve").andExpect(status().isOk)
        sendAs(committee, "PUT", "/api/societies/$societyId/finance/lock", """{}""").andExpect(status().isForbidden)

        getAs(committee, "/api/societies/$societyId/finance/audit?entityType=BOOKS")
            .andExpect(jsonPath("$[0].action").value("UNLOCK"))
            .andExpect(jsonPath("$[0].reason").value("Auditor found a missed bill"))
            .andExpect(jsonPath("$[1].action").value("LOCK"))
        // The unfiltered log works too.
        getAs(committee, "/api/societies/$societyId/finance/audit").andExpect(status().isOk)
    }

    @Test
    fun `documents attach as images or PDF, download for viewers, and stay once posted`() {
        setApprovalAbove(10000)
        val posted = idOf(payment("500"))
        val waiting = idOf(payment("20000"))
        val pdf = MockMultipartFile("file", "invoice-77.pdf", "application/pdf", "%PDF-1.4\n%fake invoice\n".toByteArray())

        val withDoc = body(mockMvc.perform(
            multipart("/api/finance/vouchers/$posted/documents").file(pdf).param("documentType", "INVOICE")
                .header("Authorization", bearer(secretary))
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.documents", hasSize<Any>(1)))
            .andExpect(jsonPath("$.documents[0].documentType").value("INVOICE"))
            .andExpect(jsonPath("$.documents[0].contentType").value("application/pdf"))
            .andExpect(jsonPath("$.documents[0].filename").value("invoice-77.pdf")))
        val documentId = JsonPath.read<Number>(withDoc, "$.documents[0].id").toLong()

        getAs(committee, "/api/finance/documents/$documentId")
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(content().bytes(pdf.bytes))
        getAs(resident, "/api/finance/documents/$documentId").andExpect(status().isForbidden)
        sendAs(secretary, "DELETE", "/api/finance/documents/$documentId").andExpect(status().isConflict)

        val text = MockMultipartFile("file", "notes.txt", "text/plain", "hello".toByteArray())
        mockMvc.perform(multipart("/api/finance/vouchers/$posted/documents").file(text).header("Authorization", bearer(secretary)))
            .andExpect(status().isBadRequest)

        val png = MockMultipartFile("file", "photo.png", "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2))
        val pending = body(mockMvc.perform(multipart("/api/finance/vouchers/$waiting/documents").file(png).header("Authorization", bearer(secretary)))
            .andExpect(status().isCreated))
        val pendingDoc = JsonPath.read<Number>(pending, "$.documents[0].id").toLong()
        sendAs(secretary, "DELETE", "/api/finance/documents/$pendingDoc").andExpect(status().isNoContent)
        getAs(committee, "/api/finance/vouchers/$waiting").andExpect(jsonPath("$.documents", hasSize<Any>(0)))
    }

    @Test
    fun `vouchers can be searched, and the day book lists the period oldest first`() {
        val hallRent = accountId("4210")
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/receipts",
            """{"date":"2026-05-02","depositAccountId":$cash,"payee":"Tony Stark","flatId":$flatId,"lines":[{"accountId":$hallRent,"amount":5000}]}""")
        val paid = idOf(payment("12000", date = "2026-06-15"))
        sendAs(secretary, "POST", "/api/finance/vouchers/$paid/void", """{"reason":"Wrong"}""")
        setApprovalAbove(10000)
        payment("20000", date = "2026-06-20")

        val base = "/api/societies/$societyId/finance/vouchers"
        getAs(committee, base).andExpect(jsonPath("$", hasSize<Any>(5))) // opening, receipt, payment, reversal, waiting
        getAs(committee, "$base?type=RECEIPT").andExpect(jsonPath("$", hasSize<Any>(1)))
        getAs(committee, "$base?status=PENDING_APPROVAL").andExpect(jsonPath("$", hasSize<Any>(1)))
        getAs(committee, "$base?q=tony").andExpect(jsonPath("$[0].payee").value("Tony Stark"))
        getAs(committee, "$base?flatId=$flatId").andExpect(jsonPath("$", hasSize<Any>(1)))
        getAs(committee, "$base?accountId=$hallRent").andExpect(jsonPath("$", hasSize<Any>(1)))
        getAs(committee, "$base?from=2026-06-01&to=2026-06-30&status=POSTED&status=VOID").andExpect(jsonPath("$", hasSize<Any>(1)))

        // In the books only: no waiting entry; the voided payment and its reversal both appear.
        getAs(committee, "/api/societies/$societyId/finance/day-book?from=2026-04-01")
            .andExpect(jsonPath("$", hasSize<Any>(4)))
            .andExpect(jsonPath("$[0].type").value("OPENING"))
            .andExpect(jsonPath("$[1].type").value("RECEIPT"))
            .andExpect(jsonPath("$[2].status").value("VOID"))
    }

    @Test
    fun `the cash and bank summary shows opening, in, out and closing per account`() {
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/contras",
            """{"date":"2026-05-05","fromAccountId":$cash,"toAccountId":$bank,"amount":4000}""")
        payment("12000", date = "2026-05-20")

        getAs(committee, "/api/societies/$societyId/finance/cash-bank?from=2026-05-01&to=2026-05-31")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accounts", hasSize<Any>(2)))
            .andExpect(jsonPath("$.accounts[?(@.accountId == $cash)].opening").value(10000.0))
            .andExpect(jsonPath("$.accounts[?(@.accountId == $cash)].paid").value(4000.0))
            .andExpect(jsonPath("$.accounts[?(@.accountId == $cash)].closing").value(6000.0))
            .andExpect(jsonPath("$.accounts[?(@.accountId == $bank)].received").value(4000.0))
            .andExpect(jsonPath("$.accounts[?(@.accountId == $bank)].paid").value(12000.0))
            .andExpect(jsonPath("$.accounts[?(@.accountId == $bank)].closing").value(42000.0))
            .andExpect(jsonPath("$.totalOpening").value(60000))
            .andExpect(jsonPath("$.totalClosing").value(48000))
    }

    @Test
    fun `default accounts must be cash or bank ledgers`() {
        sendAs(secretary, "PUT", "/api/societies/$societyId/finance/defaults",
            """{"cashDepositAccountId":$cash,"chequeDepositAccountId":$bank,"upiDepositAccountId":$bank,"transferDepositAccountId":$bank,"defaultPayingAccountId":$bank}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.defaults.upiDepositAccountId").value(bank))
        sendAs(secretary, "PUT", "/api/societies/$societyId/finance/defaults", """{"cashDepositAccountId":${accountId("5110")}}""")
            .andExpect(status().isBadRequest)
        // A default can't be deactivated.
        sendAs(secretary, "PATCH", "/api/finance/bank-accounts/$bank", """{"active":false}""").andExpect(status().isConflict)
    }
}
