package org.devvikram.societymanagement.maintenance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.maintenance.service.MaintenanceNotificationListener
import org.devvikram.societymanagement.maintenance.service.MaintenanceRefundNeedsApproval
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingsService
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// Advance payments (credit), credit spent when bills are issued, and refunds of unused credit.
// Flat 101 is billed 2625.00 per cycle, flat 102 3500.00. Real local Postgres, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class MaintenanceCreditIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var settingsService: SocietySettingsService
    @Autowired lateinit var notificationListener: MaintenanceNotificationListener
    @Autowired lateinit var pushNotifier: RecordingPushNotifier

    private var societyId = 0L
    private var flatId = 0L
    private var otherFlatId = 0L
    private lateinit var resident: User
    private lateinit var neighbour: User
    private lateinit var committee: User
    private lateinit var secretary: User
    private lateinit var secondSecretary: User

    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
    private fun period(start: String, end: String, due: String) = """{"periodStart":"$start","periodEnd":"$end","dueDate":"$due"}"""
    private val november = period("2026-11-01", "2026-11-30", "2026-11-15")
    private val december = period("2026-12-01", "2026-12-31", "2026-12-16")
    private val january = period("2027-01-01", "2027-01-31", "2027-01-16")

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
        societyId = societyService.createSociety("Credit Test Society").id!!
        val wing = wingService.addWing(societyId, "A")
        flatId = flatService.addFlat(wing.id!!, "101", BigDecimal("850")).id!!
        otherFlatId = flatService.addFlat(wing.id!!, "102", BigDecimal("1200")).id!!
        resident = user("RESIDENT", flatId = flatId)
        neighbour = user("RESIDENT", flatId = otherFlatId)
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")
        secondSecretary = user("SECRETARY")
        sendAs(secretary, "/api/societies/$societyId/maintenance/charge-heads",
            """{"name":"Maintenance","calcType":"PER_SQFT","amount":2.50}""").andExpect(status().isCreated)
        sendAs(secretary, "/api/societies/$societyId/maintenance/charge-heads",
            """{"name":"Sinking fund","calcType":"FIXED","amount":500}""").andExpect(status().isCreated)
        pushNotifier.sent.clear()
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun getAs(user: User, path: String) = mockMvc.perform(get(path).header("Authorization", bearer(user)))

    private fun sendAs(user: User, path: String, body: String? = null): ResultActions {
        val builder = post(path).header("Authorization", bearer(user))
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun body(result: ResultActions) = result.andReturn().response.contentAsString

    private fun read(result: ResultActions, path: String): Long = JsonPath.read<Number>(body(result), path).toLong()

    private fun issue(period: String = november): Long =
        read(sendAs(secretary, "/api/societies/$societyId/maintenance/cycles", period).andExpect(status().isCreated), "$.id")

    private fun billOf(cycleId: Long, flat: Long = flatId): Long =
        read(getAs(secretary, "/api/maintenance/bills?societyId=$societyId&cycleId=$cycleId&flatId=$flat"), "$[0].id")

    private fun pay(amount: String, flat: Long = flatId, extra: String = "") =
        sendAs(secretary, "/api/societies/$societyId/maintenance/payments",
            """{"flatId":$flat,"amount":$amount,"paidOn":"$today","method":"UPI"$extra}""")

    private fun refund(amount: String, by: User = secretary, flat: Long = flatId) =
        sendAs(by, "/api/societies/$societyId/maintenance/refunds",
            """{"flatId":$flat,"amount":$amount,"paidOn":"$today","method":"BANK_TRANSFER","reason":"Moved out","reference":"NEFT-9"}""")

    private fun credit(flat: Long = flatId, by: User = secretary): BigDecimal = BigDecimal(
        JsonPath.read<Any>(body(getAs(by, "/api/societies/$societyId/maintenance/flats/$flat/credit")), "$.credit").toString()
    ).setScale(2)

    private fun d(value: String) = BigDecimal(value).setScale(2)

    @Test
    fun `paying more than is owed keeps the rest as the flat's credit`() {
        val nov = issue()
        val payment = read(pay("5000", extra = ""","depositAccountId":42""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.allocations", hasSize<Any>(1)))
            .andExpect(jsonPath("$.allocations[0].amount").value(2625))
            .andExpect(jsonPath("$.allocations[0].fromCredit").value(false))
            .andExpect(jsonPath("$.creditLeft").value(2375))
            .andExpect(jsonPath("$.depositAccountId").value(42)), "$.id")
        getAs(secretary, "/api/maintenance/bills/${billOf(nov)}").andExpect(jsonPath("$.status").value("PAID"))
        assertEquals(d("2375"), credit())

        // The flat owes nothing but still has a dues row, for its credit; residents see their own.
        getAs(secretary, "/api/societies/$societyId/maintenance/dues")
            .andExpect(jsonPath("$[?(@.flatId == $flatId)].outstanding").value(0.0))
            .andExpect(jsonPath("$[?(@.flatId == $flatId)].credit").value(2375.0))
            .andExpect(jsonPath("$[?(@.flatId == $flatId)].openBills").value(0))
        getAs(resident, "/api/societies/$societyId/maintenance/dues")
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].credit").value(2375))
        getAs(neighbour, "/api/societies/$societyId/maintenance/flats/$flatId/credit").andExpect(status().isForbidden)
        getAs(secretary, "/api/maintenance/payments/$payment").andExpect(jsonPath("$.creditLeft").value(2375))
    }

    @Test
    fun `with advance payments off, paying more than is owed is refused`() {
        settingsService.update(societyId, mapOf("acceptAdvancePayments" to false), secretary.id!!)
        issue()
        pay("2625.01").andExpect(status().isBadRequest).andExpect(jsonPath("$.message", containsString("outstanding")))
        pay("2625").andExpect(status().isCreated)
    }

    @Test
    fun `credit pays the next bills when they are issued, oldest first`() {
        // Paid before any bill: all of it is credit.
        pay("6000").andExpect(status().isCreated).andExpect(jsonPath("$.allocations", hasSize<Any>(0)))
        assertEquals(d("6000"), credit())

        val nov = issue()
        getAs(secretary, "/api/maintenance/bills/${billOf(nov)}")
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.payments[0].fromCredit").value(true))
            .andExpect(jsonPath("$.payments[0].appliedOn").value("2026-11-01"))
        // The other flat had no credit and still owes its bill.
        getAs(secretary, "/api/maintenance/bills/${billOf(nov, otherFlatId)}").andExpect(jsonPath("$.status").value("UNPAID"))
        assertEquals(d("3375"), credit())

        val dec = issue(december)
        getAs(secretary, "/api/maintenance/bills/${billOf(dec)}").andExpect(jsonPath("$.status").value("PAID"))
        assertEquals(d("750"), credit())

        val jan = issue(january)
        getAs(secretary, "/api/maintenance/bills/${billOf(jan)}")
            .andExpect(jsonPath("$.status").value("PARTIAL"))
            .andExpect(jsonPath("$.amountPaid").value(750))
            .andExpect(jsonPath("$.outstanding").value(1875))
        assertEquals(d("0"), credit())
    }

    @Test
    fun `cancelling a cycle paid only by credit gives the credit back - direct payments still block it`() {
        pay("3000")
        val nov = issue()
        assertEquals(d("375"), credit())
        sendAs(secretary, "/api/maintenance/cycles/$nov/cancel", """{"reason":"Wrong rates"}""").andExpect(status().isOk)
        getAs(secretary, "/api/maintenance/bills/${billOf(nov)}")
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.amountPaid").value(0))
        assertEquals(d("3000"), credit())

        val again = issue()
        pay("100", flat = otherFlatId)
        sendAs(secretary, "/api/maintenance/cycles/$again/cancel", """{"reason":"Again"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("void those payments")))
    }

    @Test
    fun `voiding a payment also takes back the bills its credit paid`() {
        val nov = issue()
        val payment = read(pay("5250"), "$.id") // November 2625 + 2625 credit
        val dec = issue(december)
        getAs(secretary, "/api/maintenance/bills/${billOf(dec)}").andExpect(jsonPath("$.status").value("PAID"))

        sendAs(secretary, "/api/maintenance/payments/$payment/void", """{"reason":"Bounced"}""").andExpect(status().isOk)
        getAs(secretary, "/api/maintenance/bills/${billOf(nov)}").andExpect(jsonPath("$.status").value("UNPAID"))
        getAs(secretary, "/api/maintenance/bills/${billOf(dec)}").andExpect(jsonPath("$.status").value("UNPAID"))
        assertEquals(d("0"), credit())
    }

    @Test
    fun `a refund pays back unused credit and holds it until it is voided`() {
        issue()
        val payment = read(pay("5000"), "$.id")
        refund("2375.01").andExpect(status().isBadRequest).andExpect(jsonPath("$.message", containsString("unused credit 2375.00")))
        refund("100", by = committee).andExpect(status().isForbidden)

        val first = read(refund("1000")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.refundNumber").value("RF-000001"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.allocations[0].paymentId").value(payment))
            .andExpect(jsonPath("$.createdByName").value("SECRETARY user")), "$.id")
        assertEquals(d("1375"), credit())
        getAs(secretary, "/api/maintenance/payments/$payment").andExpect(jsonPath("$.creditLeft").value(1375))

        // The payment can't be voided while its credit is in a refund.
        sendAs(secretary, "/api/maintenance/payments/$payment/void", """{"reason":"x"}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.message", containsString("refund")))
        sendAs(secretary, "/api/maintenance/refunds/$first/void", """{}""").andExpect(status().isBadRequest)
        sendAs(secretary, "/api/maintenance/refunds/$first/void", """{"reason":"Cheque never sent"}""")
            .andExpect(jsonPath("$.status").value("VOID"))
            .andExpect(jsonPath("$.voidReason").value("Cheque never sent"))
        assertEquals(d("2375"), credit())
        sendAs(secretary, "/api/maintenance/refunds/$first/void", """{"reason":"again"}""").andExpect(status().isConflict)
        sendAs(secretary, "/api/maintenance/payments/$payment/void", """{"reason":"Entered twice"}""").andExpect(status().isOk)

        refund("1").andExpect(status().isBadRequest) // nothing left to refund
    }

    @Test
    fun `above the threshold a refund waits for someone else and reserves its credit meanwhile`() {
        settingsService.update(societyId, mapOf("financeApprovalAbove" to 500), secretary.id!!)
        issue()
        pay("5000")
        val waiting = read(refund("1000").andExpect(jsonPath("$.status").value("PENDING_APPROVAL")), "$.id")
        assertEquals(d("1375"), credit()) // reserved

        // Credit held by the waiting refund isn't spent on the next bill.
        val dec = issue(december)
        getAs(secretary, "/api/maintenance/bills/${billOf(dec)}").andExpect(jsonPath("$.amountPaid").value(1375))

        sendAs(secretary, "/api/maintenance/refunds/$waiting/approve").andExpect(status().isForbidden)
        sendAs(committee, "/api/maintenance/refunds/$waiting/reject", """{}""").andExpect(status().isBadRequest)
        sendAs(committee, "/api/maintenance/refunds/$waiting/approve")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.decidedByName").value("COMMITTEE user"))
        sendAs(committee, "/api/maintenance/refunds/$waiting/approve").andExpect(status().isConflict)
    }

    @Test
    fun `a rejected or withdrawn refund gives its credit back`() {
        settingsService.update(societyId, mapOf("financeApprovalAbove" to 500), secretary.id!!)
        pay("3000")
        val rejected = read(refund("1000"), "$.id")
        sendAs(committee, "/api/maintenance/refunds/$rejected/reject", """{"reason":"Dues coming"}""")
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.decisionReason").value("Dues coming"))
        assertEquals(d("3000"), credit())

        val withdrawn = read(refund("2000"), "$.id")
        sendAs(secondSecretary, "/api/maintenance/refunds/$withdrawn/withdraw").andExpect(status().isForbidden)
        sendAs(secretary, "/api/maintenance/refunds/$withdrawn/withdraw")
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.decisionReason").value("Withdrawn by the maker"))
        assertEquals(d("3000"), credit())
        refund("400").andExpect(jsonPath("$.refundNumber").value("RF-000003")).andExpect(jsonPath("$.status").value("COMPLETED"))
    }

    @Test
    fun `residents see their own flat's refunds only`() {
        pay("3000")
        pay("3000", flat = otherFlatId)
        refund("100")
        refund("200", flat = otherFlatId)
        getAs(resident, "/api/maintenance/refunds?societyId=$societyId")
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].flatNumber").value("101"))
        getAs(resident, "/api/maintenance/refunds?societyId=$societyId&flatId=$otherFlatId").andExpect(status().isForbidden)
        getAs(committee, "/api/maintenance/refunds?societyId=$societyId").andExpect(jsonPath("$", hasSize<Any>(2)))
        getAs(committee, "/api/maintenance/refunds?societyId=$societyId&status=PENDING_APPROVAL").andExpect(jsonPath("$", hasSize<Any>(0)))
        assertEquals(d("2900"), credit(by = resident))
    }

    @Test
    fun `refund approvers get a push, the maker doesn't`() {
        userService.updateProfile(committee.id!!, UpdateProfileCommand(fcmToken = "committee-token"))
        userService.updateProfile(secretary.id!!, UpdateProfileCommand(fcmToken = "secretary-token"))
        userService.updateProfile(resident.id!!, UpdateProfileCommand(fcmToken = "resident-token"))
        notificationListener.onRefundNeedsApproval(
            MaintenanceRefundNeedsApproval(1, societyId, "RF-000009", "101", BigDecimal("1000.00"), secretary.id!!, "SECRETARY user")
        )
        val push = pushNotifier.sent.single()
        assertTrue("committee-token" in push.tokens)
        assertFalse("secretary-token" in push.tokens)
        assertFalse("resident-token" in push.tokens)
        assertTrue(push.body.startsWith("SECRETARY user recorded refund RF-000009"))
    }
}
