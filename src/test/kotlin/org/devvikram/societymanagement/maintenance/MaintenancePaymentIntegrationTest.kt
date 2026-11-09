package org.devvikram.societymanagement.maintenance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.maintenance.service.MaintenanceDuesReminder
import org.devvikram.societymanagement.maintenance.service.MaintenanceNotificationListener
import org.devvikram.societymanagement.maintenance.service.MaintenancePaymentRecorded
import org.devvikram.societymanagement.maintenance.service.OverdueFlat
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
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// Payments, late fees, opening balances and dues reports end to end (M7b). Same annotations and imported
// config as MaintenanceIntegrationTest, so both share one Spring context (and its connection pool).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class MaintenancePaymentIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var notificationListener: MaintenanceNotificationListener
    @Autowired lateinit var pushNotifier: RecordingPushNotifier
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var settingsService: org.devvikram.societymanagement.society.service.SocietySettingsService

    private var societyId = 0L
    private var flatId = 0L
    private var otherFlatId = 0L
    private lateinit var resident: User
    private lateinit var neighbour: User
    private lateinit var committee: User
    private lateinit var secretary: User

    // Payments can't be dated in the future, so they use today (in the societies' zone).
    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()

    private fun period(start: String, end: String, due: String) =
        """{"periodStart":"$start","periodEnd":"$end","dueDate":"$due"}"""

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
        societyId = societyService.createSociety("Payments Test Society").id!!
        val wing = wingService.addWing(societyId, "A")
        flatId = flatService.addFlat(wing.id!!, "101", BigDecimal("850")).id!!
        otherFlatId = flatService.addFlat(wing.id!!, "102", BigDecimal("1200")).id!!
        resident = user("RESIDENT", flatId = flatId)
        neighbour = user("RESIDENT", flatId = otherFlatId)
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun getAs(user: User, path: String) = mockMvc.perform(get(path).header("Authorization", bearer(user)))

    private fun sendAs(user: User, path: String, body: String? = null, method: String = "POST"): ResultActions {
        val builder = (if (method == "PUT") put(path) else post(path)).header("Authorization", bearer(user))
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun read(result: ResultActions, path: String): Long =
        JsonPath.read<Number>(result.andReturn().response.contentAsString, path).toLong()

    // Flat 101 is billed 2625.00 and flat 102 3500.00 per cycle.
    private fun issue(body: String = november): Long {
        if (read(getAs(secretary, "/api/societies/$societyId/maintenance/charge-heads"), "$.length()") == 0L) {
            sendAs(secretary, "/api/societies/$societyId/maintenance/charge-heads",
                """{"name":"Maintenance","calcType":"PER_SQFT","amount":2.50}""").andExpect(status().isCreated)
            sendAs(secretary, "/api/societies/$societyId/maintenance/charge-heads",
                """{"name":"Sinking fund","calcType":"FIXED","amount":500}""").andExpect(status().isCreated)
        }
        return read(sendAs(secretary, "/api/societies/$societyId/maintenance/cycles", body).andExpect(status().isCreated), "$.id")
    }

    private fun billOf(cycleId: Long, flat: Long = flatId): Long =
        read(getAs(secretary, "/api/maintenance/bills?societyId=$societyId&cycleId=$cycleId&flatId=$flat"), "$[0].id")

    private fun pay(amount: String, flat: Long = flatId, extra: String = "", by: User = secretary) =
        sendAs(by, "/api/societies/$societyId/maintenance/payments",
            """{"flatId":$flat,"amount":$amount,"paidOn":"$today","method":"UPI","reference":"UPI-123"$extra}""")

    private fun openingBalance(flat: Long, amount: String, due: String = "2026-10-01") =
        sendAs(secretary, "/api/societies/$societyId/maintenance/opening-balances",
            """{"asOf":"2026-09-30","dueDate":"$due","entries":[{"flatId":$flat,"amount":$amount}]}""")

    @Test
    fun `a payment is split oldest first and the bills follow it`() {
        val nov = issue()
        val dec = issue(december)

        val first = pay("3000").andExpect(status().isCreated)
            .andExpect(jsonPath("$.receiptNumber").value("R-000001"))
            .andExpect(jsonPath("$.status").value("RECORDED"))
            .andExpect(jsonPath("$.allocations", hasSize<Any>(2)))
            .andExpect(jsonPath("$.allocations[0].amount").value(2625.00))
            .andExpect(jsonPath("$.allocations[1].amount").value(375.00))
        pay("2250").andExpect(jsonPath("$.receiptNumber").value("R-000002"))

        getAs(resident, "/api/maintenance/bills/${billOf(nov)}")
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.outstanding").value(0))
            .andExpect(jsonPath("$.payments", hasSize<Any>(1)))
            .andExpect(jsonPath("$.payments[0].receiptNumber").value("R-000001"))
        getAs(resident, "/api/maintenance/bills/${billOf(dec)}")
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.payments", hasSize<Any>(2)))

        getAs(committee, "/api/maintenance/cycles/$nov")
            .andExpect(jsonPath("$.totalPaid").value(2625.00))
            .andExpect(jsonPath("$.totalOutstanding").value(3500.00))
            .andExpect(jsonPath("$.billsByStatus.PAID").value(1))
            .andExpect(jsonPath("$.billsByStatus.UNPAID").value(1))

        getAs(resident, "/api/maintenance/payments/${read(first, "$.id")}").andExpect(status().isOk)
        getAs(neighbour, "/api/maintenance/payments/${read(first, "$.id")}").andExpect(status().isForbidden)
        getAs(neighbour, "/api/maintenance/payments?societyId=$societyId").andExpect(jsonPath("$", hasSize<Any>(0)))
        getAs(committee, "/api/maintenance/payments?societyId=$societyId").andExpect(jsonPath("$", hasSize<Any>(2)))
    }

    @Test
    fun `a payment can be aimed at one bill, and more than the outstanding is refused when advances are off`() {
        val nov = issue()
        val dec = issue(december)

        pay("1000", extra = ""","billId":${billOf(dec)}""").andExpect(status().isCreated)
            .andExpect(jsonPath("$.allocations[0].billNumber").value("202612-0001"))
        getAs(secretary, "/api/maintenance/bills/${billOf(dec)}").andExpect(jsonPath("$.status").value("PARTIAL"))
        getAs(secretary, "/api/maintenance/bills/${billOf(nov)}").andExpect(jsonPath("$.status").value("UNPAID"))

        settingsService.update(societyId, mapOf("acceptAdvancePayments" to false), secretary.id!!)
        pay("4250.01").andExpect(status().isBadRequest).andExpect(jsonPath("$.message", containsString("outstanding 4250.00")))
        pay("100", extra = ""","billId":${billOf(nov, otherFlatId)}""").andExpect(status().isBadRequest)
        pay("100", by = committee).andExpect(status().isForbidden)
        pay("100", by = resident).andExpect(status().isForbidden)
        sendAs(secretary, "/api/societies/$societyId/maintenance/payments",
            """{"flatId":$flatId,"amount":100,"paidOn":"${LocalDate.parse(today).plusDays(1)}","method":"CASH"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `voiding a payment reopens its bills, and cycles with payments can't be cancelled`() {
        val nov = issue()
        val payment = read(pay("2625"), "$.id")

        sendAs(secretary, "/api/maintenance/cycles/$nov/cancel", """{"reason":"Wrong"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("void those payments")))

        sendAs(committee, "/api/maintenance/payments/$payment/void", """{"reason":"Bounced"}""").andExpect(status().isForbidden)
        sendAs(secretary, "/api/maintenance/payments/$payment/void", """{"reason":"Cheque bounced"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("VOID"))
            .andExpect(jsonPath("$.voidReason").value("Cheque bounced"))
        sendAs(secretary, "/api/maintenance/payments/$payment/void", """{"reason":"Again"}""").andExpect(status().isConflict)

        getAs(secretary, "/api/maintenance/bills/${billOf(nov)}")
            .andExpect(jsonPath("$.status").value("UNPAID"))
            .andExpect(jsonPath("$.amountPaid").value(0))
            .andExpect(jsonPath("$.payments", hasSize<Any>(0)))
        getAs(secretary, "/api/maintenance/payments?societyId=$societyId&includeVoid=false").andExpect(jsonPath("$", hasSize<Any>(0)))
        sendAs(secretary, "/api/maintenance/cycles/$nov/cancel", """{"reason":"Wrong"}""").andExpect(status().isOk)
    }

    @Test
    fun `waiving a partly paid bill writes off the rest and locks its payments`() {
        val bill = billOf(issue())
        val payment = read(pay("1000"), "$.id")

        sendAs(secretary, "/api/maintenance/bills/$bill/waive", """{"reason":"Hardship"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("WAIVED"))
            .andExpect(jsonPath("$.amountPaid").value(1000.00))
            .andExpect(jsonPath("$.outstanding").value(0))
        sendAs(secretary, "/api/maintenance/payments/$payment/void", """{"reason":"Oops"}""").andExpect(status().isConflict)
        // Nothing is owed any more, so a new payment is all credit (advances are on by default).
        pay("1").andExpect(status().isCreated).andExpect(jsonPath("$.allocations", hasSize<Any>(0))).andExpect(jsonPath("$.creditLeft").value(1))
    }

    @Test
    fun `late fees are added to the next cycle and charged again only after a cancellation`() {
        issue()
        sendAs(secretary, "/api/societies/$societyId/maintenance/settings",
            """{"billingFrequency":"MONTHLY","dueDays":15,"roundToRupee":true,"lateFeeType":"FIXED","lateFeeValue":100,"graceDays":5}""",
            method = "PUT")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lateFeeType").value("FIXED"))
            .andExpect(jsonPath("$.graceDays").value(5))
        // Flat 102 pays in time and gets no late charge.
        pay("3500", flat = otherFlatId)

        sendAs(secretary, "/api/societies/$societyId/maintenance/cycles/preview", december)
            .andExpect(jsonPath("$.bills[0].total").value(2725.00))
            .andExpect(jsonPath("$.bills[0].lines[2].calcType").value("LATE_FEE"))
            .andExpect(jsonPath("$.bills[0].lines[2].note").value("Bill 202611-0001, due 2026-11-15"))
            .andExpect(jsonPath("$.bills[1].total").value(3500.00))
        val dec = issue(december)
        sendAs(secretary, "/api/maintenance/cycles/$dec/cancel", """{"reason":"Redo"}""").andExpect(status().isOk)
        val decAgain = issue(december)
        getAs(resident, "/api/maintenance/bills/${billOf(decAgain)}").andExpect(jsonPath("$.total").value(2725.00))

        // FIXED is charged once: January adds no second fee for the November bill, only for December's.
        sendAs(secretary, "/api/societies/$societyId/maintenance/cycles/preview", period("2027-01-01", "2027-01-31", "2027-01-16"))
            .andExpect(jsonPath("$.bills[0].lines", hasSize<Any>(3)))
            .andExpect(jsonPath("$.bills[0].lines[2].note").value("Bill 202612-0001, due 2026-12-16"))
    }

    @Test
    fun `late fee settings are validated and kept when a client leaves them out`() {
        val settings = "/api/societies/$societyId/maintenance/settings"
        sendAs(secretary, settings,
            """{"billingFrequency":"MONTHLY","dueDays":15,"roundToRupee":true,"lateFeeType":"SIMPLE_INTEREST","lateFeeValue":0}""",
            method = "PUT").andExpect(status().isBadRequest)
        sendAs(secretary, settings,
            """{"billingFrequency":"MONTHLY","dueDays":15,"roundToRupee":true,"lateFeeType":"SIMPLE_INTEREST","lateFeeValue":18}""",
            method = "PUT").andExpect(status().isOk)
        sendAs(secretary, settings, """{"billingFrequency":"QUARTERLY","dueDays":10,"roundToRupee":true}""", method = "PUT")
            .andExpect(jsonPath("$.lateFeeType").value("SIMPLE_INTEREST"))
            .andExpect(jsonPath("$.lateFeeValue").value(18.00))
    }

    @Test
    fun `the society UPI ID is validated, kept when left out and removed when blank`() {
        val settings = "/api/societies/$societyId/maintenance/settings"
        val base = """"billingFrequency":"MONTHLY","dueDays":15,"roundToRupee":true"""
        sendAs(secretary, settings, """{$base,"upiId":"not a upi id"}""", method = "PUT")
            .andExpect(status().isBadRequest)
        sendAs(secretary, settings, """{$base,"upiId":" stark.tower@okhdfc ","upiPayeeName":"Stark Tower CHS"}""", method = "PUT")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.upiId").value("stark.tower@okhdfc"))
        sendAs(secretary, settings, """{$base}""", method = "PUT")
            .andExpect(jsonPath("$.upiId").value("stark.tower@okhdfc"))
            .andExpect(jsonPath("$.upiPayeeName").value("Stark Tower CHS"))
        // Residents read it to show the QR code on their bills.
        getAs(resident, settings).andExpect(jsonPath("$.upiId").value("stark.tower@okhdfc"))
        sendAs(secretary, settings, """{$base,"upiId":"","upiPayeeName":""}""", method = "PUT")
            .andExpect(jsonPath("$.upiId").doesNotExist())
    }

    @Test
    fun `opening balances are one-off bills that payments clear first`() {
        openingBalance(flatId, "5000").andExpect(status().isCreated)
            .andExpect(jsonPath("$.kind").value("OPENING_BALANCE"))
            .andExpect(jsonPath("$.totalBilled").value(5000.00))
        openingBalance(otherFlatId, "100").andExpect(status().isConflict)
        sendAs(secretary, "/api/societies/$societyId/maintenance/opening-balances",
            """{"asOf":"2026-09-30","dueDate":"2026-10-01","entries":[{"flatId":$flatId,"amount":0}]}""")
            .andExpect(status().isBadRequest)

        // The opening cycle doesn't count as a regular period.
        getAs(secretary, "/api/societies/$societyId/maintenance/cycles/next")
            .andExpect(jsonPath("$.periodStart", startsWith(LocalDate.parse(today).withDayOfMonth(1).toString())))
        issue()

        pay("6000").andExpect(status().isCreated)
            .andExpect(jsonPath("$.allocations[0].billNumber").value("OB-0001"))
            .andExpect(jsonPath("$.allocations[0].amount").value(5000.00))
            .andExpect(jsonPath("$.allocations[1].amount").value(1000.00))
    }

    @Test
    fun `dues list what each flat owes and defaulters can be reminded`() {
        openingBalance(flatId, "5000")
        // Far in the future, so never overdue whenever the test runs.
        issue(period("2099-01-01", "2099-01-31", "2099-01-15"))

        getAs(committee, "/api/societies/$societyId/maintenance/dues")
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].flatNumber").value("101"))
            .andExpect(jsonPath("$[0].openBills").value(2))
            .andExpect(jsonPath("$[0].outstanding").value(7625.00))
            .andExpect(jsonPath("$[0].overdue").value(5000.00))
            .andExpect(jsonPath("$[0].oldestDueDate").value("2026-10-01"))
            .andExpect(jsonPath("$[1].overdue").value(0))
        getAs(committee, "/api/societies/$societyId/maintenance/dues?overdueOnly=true").andExpect(jsonPath("$", hasSize<Any>(1)))
        getAs(neighbour, "/api/societies/$societyId/maintenance/dues")
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].flatNumber").value("102"))

        getAs(committee, "/api/societies/$societyId/maintenance/dues/export")
            .andExpect(content().string(startsWith("wing,flat,open_bills,outstanding,overdue,oldest_due_date")))
            .andExpect(content().string(containsString("A,101,2,7625.00,5000.00,2026-10-01")))
        getAs(resident, "/api/societies/$societyId/maintenance/dues/export").andExpect(status().isForbidden)

        sendAs(committee, "/api/societies/$societyId/maintenance/dues/remind").andExpect(status().isForbidden)
        sendAs(secretary, "/api/societies/$societyId/maintenance/dues/remind")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.flatCount").value(1))
            .andExpect(jsonPath("$.totalOverdue").value(5000.00))
        sendAs(secretary, "/api/societies/$societyId/maintenance/dues/remind", """{"flatIds":[$otherFlatId]}""")
            .andExpect(jsonPath("$.flatCount").value(0))
    }

    @Test
    fun `the committee exports payments as CSV`() {
        issue()
        pay("1000")

        getAs(committee, "/api/societies/$societyId/maintenance/payments/export?from=$today&to=$today")
            .andExpect(status().isOk)
            .andExpect(content().string(startsWith("receipt_number,paid_on,wing,flat,amount,method,reference,bills,status")))
            .andExpect(content().string(containsString("R-000001,$today,A,101,1000.00,UPI,UPI-123,202611-0001: 1000.00,RECORDED")))
        getAs(resident, "/api/societies/$societyId/maintenance/payments/export?from=$today&to=$today")
            .andExpect(status().isForbidden)
    }

    @Test
    fun `payment and reminder pushes go to the flat's residents only`() {
        userService.updateProfile(resident.id!!, UpdateProfileCommand(fcmToken = "resident-token"))
        userService.updateProfile(neighbour.id!!, UpdateProfileCommand(fcmToken = "neighbour-token"))
        pushNotifier.sent.clear()

        notificationListener.onPaymentRecorded(
            MaintenancePaymentRecorded(7L, societyId, flatId, "101", "R-000001", BigDecimal("2625.00"))
        )
        notificationListener.onDuesReminder(
            MaintenanceDuesReminder(societyId, listOf(OverdueFlat(otherFlatId, "102", BigDecimal("3500.00"))))
        )

        assertEquals(2, pushNotifier.sent.size)
        val (payment, reminder) = pushNotifier.sent
        assertEquals(listOf("resident-token"), payment.tokens.toList())
        assertEquals("₹2,625.00 received for flat 101 (receipt R-000001)", payment.body)
        assertEquals("MAINTENANCE_PAYMENT_RECORDED", payment.data["type"])
        assertEquals(listOf("neighbour-token"), reminder.tokens.toList())
        assertEquals("MAINTENANCE_DUES_REMINDER", reminder.data["type"])
    }
}
