package org.devvikram.societymanagement.subscription

import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyProfileCommand
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.subscription.model.InvoiceStatus
import org.devvikram.societymanagement.subscription.model.SubscriptionEventKind
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.subscription.repository.InvoiceRepository
import org.devvikram.societymanagement.subscription.repository.PlanRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionEventRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionRepository
import org.devvikram.societymanagement.subscription.service.BillingRunService
import org.devvikram.societymanagement.subscription.service.InvoiceReminder
import org.devvikram.societymanagement.subscription.service.SubscriptionService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The daily billing run (dates passed in, so no clock tricks), suspension of paid committee work, and plan
// modules enforced with 402. Billing switched on; grace 15 days; reminders 3 days before and 1 and 7 after.
@SpringBootTest(properties = ["app.billing.enabled=true"])
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class BillingRunIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var subscriptionService: SubscriptionService
    @Autowired lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired lateinit var eventRepository: SubscriptionEventRepository
    @Autowired lateinit var invoiceRepository: InvoiceRepository
    @Autowired lateinit var planRepository: PlanRepository
    @Autowired lateinit var billingRun: BillingRunService
    @Autowired lateinit var events: ApplicationEvents

    private var societyId = 0L
    private var flatId = 0L
    private lateinit var superAdmin: User
    private lateinit var secretary: User
    private lateinit var guard: User
    private lateinit var resident: User

    private val today get() = subscriptionService.today()

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(role: String, society: Long?, flat: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$role user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = role, societyId = society, flatId = flat),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Billing Run Society").id!!
        societyService.updateProfile(societyId, SocietyProfileCommand(address = "1 Road", city = "City", state = "Maharashtra", pincode = "411001"))
        val wing = wingService.addWing(societyId, "A").id!!
        listOf("101", "102").forEach { flatService.addFlat(wing, it) }
        flatId = flatService.listByWing(wing).first().id!!
        superAdmin = user("SUPER_ADMIN", null)
        secretary = user("SECRETARY", societyId)
        guard = user("GUARD", societyId)
        resident = user("RESIDENT", societyId, flatId)
        // The trial is over: the first invoice is due today.
        val s = subscriptionRepository.findBySociety(societyId)!!
        s.nextInvoiceOn = today
        subscriptionRepository.save(s)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun call(user: User, method: HttpMethod, path: String, body: String? = null): ResultActions {
        val req = request(method, path).header("Authorization", bearer(user))
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(req)
    }

    private fun subStatus() = subscriptionRepository.findBySociety(societyId)!!.status

    // The oldest invoice (the catch-up may already have issued the next one).
    private fun invoice() = invoiceRepository.findBySociety(societyId).minBy { it.id!! }

    private fun suspend() {
        billingRun.runOne(societyId, today)
        val dueOn = invoice().dueOn
        billingRun.runOne(societyId, dueOn.plusDays(16))
        assertEquals(SubscriptionStatus.SUSPENDED, subStatus())
    }

    private fun chargeHead(user: User, name: String = "Head ${(1000..9999).random()}") = call(
        user, HttpMethod.POST, "/api/societies/$societyId/maintenance/charge-heads", """{"name":"$name","calcType":"FIXED","amount":500}""",
    )

    @Test
    fun `the run issues the due invoice, then PAST_DUE after the due date and SUSPENDED after the grace period`() {
        val first = billingRun.runOne(societyId, today)
        assertEquals(1, first.invoiced)
        assertEquals(SubscriptionStatus.ACTIVE, subStatus())
        val dueOn = invoice().dueOn

        // Running again the same day changes nothing.
        assertEquals(0, billingRun.runOne(societyId, today).invoiced)

        billingRun.runOne(societyId, dueOn)
        assertEquals(SubscriptionStatus.ACTIVE, subStatus())
        assertEquals(1, billingRun.runOne(societyId, dueOn.plusDays(1)).pastDue)
        assertEquals(SubscriptionStatus.PAST_DUE, subStatus())
        billingRun.runOne(societyId, dueOn.plusDays(15))
        assertEquals(SubscriptionStatus.PAST_DUE, subStatus())
        assertEquals(1, billingRun.runOne(societyId, dueOn.plusDays(16)).suspended)
        assertEquals(SubscriptionStatus.SUSPENDED, subStatus())

        val history = eventRepository.findBySubscription(subscriptionRepository.findBySociety(societyId)!!.id!!)
            .filter { it.kind == SubscriptionEventKind.STATUS_CHANGED }.map { it.toValue }
        assertTrue(history.containsAll(listOf("PAST_DUE", "SUSPENDED")))
    }

    @Test
    fun `reminders go out on the chosen days, once each`() {
        billingRun.runOne(societyId, today)
        val dueOn = invoice().dueOn
        assertEquals(1, billingRun.runOne(societyId, dueOn.minusDays(3)).reminded)
        assertEquals(0, billingRun.runOne(societyId, dueOn.minusDays(3)).reminded)
        assertEquals(0, billingRun.runOne(societyId, dueOn.minusDays(2)).reminded)
        assertEquals(1, billingRun.runOne(societyId, dueOn.plusDays(1)).reminded)
        assertEquals(1, billingRun.runOne(societyId, dueOn.plusDays(7)).reminded)
        assertEquals(3, events.stream(InvoiceReminder::class.java).count())
        assertEquals(dueOn.plusDays(7), invoice().lastRemindedOn)
    }

    @Test
    fun `a society several periods behind gets each period's invoice`() {
        val s = subscriptionRepository.findBySociety(societyId)!!
        s.nextInvoiceOn = today.minusMonths(2)
        subscriptionRepository.save(s)
        assertEquals(3, billingRun.runOne(societyId, today).invoiced)
        assertEquals(today.plusMonths(1), subscriptionRepository.findBySociety(societyId)!!.nextInvoiceOn)
    }

    @Test
    fun `nothing is gated while paid up, and a paid invoice is never reminded or overdue`() {
        billingRun.runOne(societyId, today)
        val inv = invoice()
        call(superAdmin, HttpMethod.POST, "/api/platform-invoices/${inv.id}/payments", """{"amount":${inv.total},"mode":"UPI"}""")
            .andExpect(status().isCreated)
        val later = billingRun.runOne(societyId, inv.dueOn.plusDays(20))
        assertEquals(0, later.reminded)
        assertEquals(SubscriptionStatus.ACTIVE, subStatus())
        chargeHead(secretary).andExpect(status().isCreated)
    }

    @Test
    fun `a suspended society can't do paid committee work, but visitors, complaints and the subscription keep working`() {
        suspend()
        val inv = invoice()

        chargeHead(secretary)
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.message").value(containsString("Pay invoice ${inv.number}")))
        // Reading paid modules still works.
        call(secretary, HttpMethod.GET, "/api/societies/$societyId/maintenance/charge-heads").andExpect(status().isOk)

        // Guards still log visitors.
        val photo = MockMultipartFile("photo", "v.jpg", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0))
        mockMvc.perform(
            multipart("/api/visitors").file(photo).param("flatId", flatId.toString()).param("visitorName", "Courier")
                .header("Authorization", bearer(guard))
        ).andExpect(status().`is`(not(402)))

        // The society sees why and can still see its invoices.
        call(resident, HttpMethod.GET, "/api/societies/$societyId/subscription/access")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.readOnly").value(true))
            .andExpect(jsonPath("$.status").value("SUSPENDED"))
            .andExpect(jsonPath("$.overdueInvoiceNumber").value(inv.number))
        call(secretary, HttpMethod.GET, "/api/societies/$societyId/subscription/invoices").andExpect(status().isOk)

        // The platform is never gated.
        chargeHead(superAdmin).andExpect(status().`is`(not(402)))

        // Paying lifts it at once.
        call(superAdmin, HttpMethod.POST, "/api/platform-invoices/${inv.id}/payments", """{"amount":${inv.balance},"mode":"BANK_TRANSFER","reference":"NEFT1"}""")
            .andExpect(status().isCreated)
        assertEquals(SubscriptionStatus.ACTIVE, subStatus())
        assertEquals(InvoiceStatus.PAID, invoice().status)
        chargeHead(secretary).andExpect(status().isCreated)
        call(resident, HttpMethod.GET, "/api/societies/$societyId/subscription/access").andExpect(jsonPath("$.readOnly").value(false))
    }

    @Test
    fun `a Basic society gets 402 on finance, policies and meetings, while maintenance and core modules work`() {
        val basic = planRepository.findByCode("BASIC")!!
        val s = subscriptionRepository.findBySociety(societyId)!!
        s.planId = basic.id!!
        subscriptionRepository.save(s)

        call(secretary, HttpMethod.GET, "/api/societies/$societyId/finance/accounts")
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.message").value(containsString("Basic")))
        call(secretary, HttpMethod.GET, "/api/societies/$societyId/policies").andExpect(status().isPaymentRequired)
        call(resident, HttpMethod.GET, "/api/societies/$societyId/meetings").andExpect(status().isPaymentRequired)
        chargeHead(secretary).andExpect(status().isCreated)
        call(resident, HttpMethod.GET, "/api/societies/$societyId/subscription/access")
            .andExpect(jsonPath("$.modules.length()").value(1))
            .andExpect(jsonPath("$.modules[0]").value("MAINTENANCE"))
    }

    @Test
    fun `outsiders can't read a society's subscription access`() {
        val other = societyService.createSociety("Elsewhere").id!!
        val outsider = user("SECRETARY", other)
        call(outsider, HttpMethod.GET, "/api/societies/$societyId/subscription/access").andExpect(status().isForbidden)
    }
}
