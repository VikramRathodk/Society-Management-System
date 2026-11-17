package org.devvikram.societymanagement.subscription

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyProfileCommand
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.subscription.model.InvoiceStatus
import org.devvikram.societymanagement.subscription.model.PaymentMode
import org.devvikram.societymanagement.subscription.repository.InvoiceRepository
import org.devvikram.societymanagement.subscription.repository.PaymentRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionRepository
import org.devvikram.societymanagement.subscription.service.RazorpayClient
import org.devvikram.societymanagement.subscription.service.RazorpaySettings
import org.devvikram.societymanagement.subscription.service.SubscriptionService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@TestConfiguration
class FakeRazorpayConfig {
    @Bean
    @Primary
    fun fakeRazorpayClient() = FakeRazorpayClient()
}

// Answers order ids without calling Razorpay, and remembers what was asked.
class FakeRazorpayClient : RazorpayClient {
    private val counter = AtomicInteger()
    val created = mutableListOf<Long>()

    override fun createOrder(amountPaise: Long, receipt: String, notes: Map<String, String>): String {
        created += amountPaise
        return "order_test${System.nanoTime()}${counter.incrementAndGet()}"
    }
}

// Paying an invoice through Razorpay with the gateway faked; signatures are computed with the test secrets.
@SpringBootTest(
    properties = [
        "app.billing.enabled=true",
        "app.razorpay.key-id=rzp_test_key",
        "app.razorpay.key-secret=test_key_secret",
        "app.razorpay.webhook-secret=test_webhook_secret",
    ]
)
@AutoConfigureMockMvc
@Transactional
@Import(FakeRazorpayConfig::class)
class RazorpayIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var subscriptionService: SubscriptionService
    @Autowired lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired lateinit var invoiceRepository: InvoiceRepository
    @Autowired lateinit var paymentRepository: PaymentRepository
    @Autowired lateinit var fake: FakeRazorpayClient

    private var societyId = 0L
    private var invoiceId = 0L
    private lateinit var superAdmin: User
    private lateinit var secretary: User
    private lateinit var resident: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(role: String, society: Long?, flat: Long? = null) = userService.createUser(
        CreateUserCommand(name = "$role user", phone = randomPhone(), rawPassword = "secret123", role = AssignRoleCommand(roleCode = role, societyId = society, flatId = flat))
    )

    @BeforeEach
    fun setUp() {
        fake.created.clear()
        societyId = societyService.createSociety("Razorpay Society").id!!
        societyService.updateProfile(societyId, SocietyProfileCommand(address = "1 Road", city = "City", state = "Karnataka", pincode = "560001"))
        val wing = wingService.addWing(societyId, "A").id!!
        flatService.addFlat(wing, "101")
        superAdmin = user("SUPER_ADMIN", null)
        secretary = user("SECRETARY", societyId)
        resident = user("RESIDENT", societyId, flatService.listByWing(wing).first().id!!)
        val s = subscriptionRepository.findBySociety(societyId)!!
        s.nextInvoiceOn = subscriptionService.today()
        subscriptionRepository.save(s)
        val json = call(superAdmin, HttpMethod.POST, "/api/societies/$societyId/subscription/invoices/issue-due")
            .andExpect(status().isCreated).andReturn().response.contentAsString
        invoiceId = JsonPath.read<Number>(json, "$.summary.id").toLong()
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun call(user: User, method: HttpMethod, path: String, body: String? = null): ResultActions {
        val req = request(method, path).header("Authorization", bearer(user))
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(req)
    }

    private fun order(): Pair<String, Long> {
        val json = call(secretary, HttpMethod.POST, "/api/platform-invoices/$invoiceId/razorpay-order")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.keyId").value("rzp_test_key"))
            .andExpect(jsonPath("$.currency").value("INR"))
            .andReturn().response.contentAsString
        return JsonPath.read<String>(json, "$.orderId") to JsonPath.read<Number>(json, "$.amount").toLong()
    }

    private fun verify(orderId: String, paymentId: String, signature: String = RazorpaySettings.hmac("test_key_secret", "$orderId|$paymentId")) =
        call(secretary, HttpMethod.POST, "/api/platform-invoices/$invoiceId/razorpay-verify", """{"orderId":"$orderId","paymentId":"$paymentId","signature":"$signature"}""")

    private fun captured(orderId: String, paymentId: String, amount: Long) =
        """{"event":"payment.captured","payload":{"payment":{"entity":{"id":"$paymentId","order_id":"$orderId","amount":$amount,"status":"captured"}}}}"""

    private fun webhook(body: String, eventId: String, signature: String = RazorpaySettings.hmac("test_webhook_secret", body)) =
        mockMvc.perform(
            post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", signature).header("X-Razorpay-Event-Id", eventId)
        )

    private fun invoice() = invoiceRepository.findById(invoiceId)!!

    @Test
    fun `an order is for the amount due in paise and is reused while open`() {
        val (orderId, amount) = order()
        assertEquals(invoice().balance.movePointRight(2).toLong(), amount)
        assertEquals(orderId, order().first)
        assertEquals(1, fake.created.size)
    }

    @Test
    fun `a valid checkout signature records the payment with a receipt`() {
        val (orderId, _) = order()
        verify(orderId, "pay_1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.status").value("PAID"))
        val payment = paymentRepository.findByGatewayId("pay_1")!!
        assertEquals(PaymentMode.RAZORPAY, payment.mode)
        assertEquals(InvoiceStatus.PAID, invoice().status)
    }

    @Test
    fun `an invalid checkout signature records nothing`() {
        val (orderId, _) = order()
        verify(orderId, "pay_2", signature = "deadbeef").andExpect(status().isBadRequest)
        assertEquals(null, paymentRepository.findByGatewayId("pay_2"))
        assertEquals(InvoiceStatus.ISSUED, invoice().status)
    }

    @Test
    fun `a closed tab is still recorded by the webhook, and a repeated webhook records once`() {
        val (orderId, amount) = order()
        val body = captured(orderId, "pay_3", amount)
        webhook(body, "evt_1").andExpect(status().isOk).andExpect(jsonPath("$.outcome").value(startsWith("recorded")))
        webhook(body, "evt_1").andExpect(status().isOk).andExpect(jsonPath("$.outcome").value("duplicate"))
        // Razorpay may also send the same payment under another event id; the browser may come back late.
        webhook(body, "evt_2").andExpect(jsonPath("$.outcome").value("already recorded"))
        verify(orderId, "pay_3").andExpect(status().isOk)
        assertEquals(1, paymentRepository.findByInvoice(invoiceId).size)
        assertEquals(InvoiceStatus.PAID, invoice().status)
    }

    @Test
    fun `a webhook with a bad signature is refused`() {
        val (orderId, amount) = order()
        webhook(captured(orderId, "pay_4", amount), "evt_3", signature = "0000").andExpect(status().isBadRequest)
        assertEquals(null, paymentRepository.findByGatewayId("pay_4"))
    }

    @Test
    fun `an amount that doesn't match the order is not recorded`() {
        val (orderId, amount) = order()
        webhook(captured(orderId, "pay_5", amount - 100), "evt_4")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value(containsString("doesn't match")))
        assertEquals(null, paymentRepository.findByGatewayId("pay_5"))
        assertEquals(InvoiceStatus.ISSUED, invoice().status)
    }

    @Test
    fun `a payment made after the invoice was paid another way is flagged for refund`() {
        val (orderId, amount) = order()
        call(superAdmin, HttpMethod.POST, "/api/platform-invoices/$invoiceId/payments", """{"amount":${invoice().balance},"mode":"BANK_TRANSFER"}""")
            .andExpect(status().isCreated)
        webhook(captured(orderId, "pay_6", amount), "evt_5").andExpect(jsonPath("$.outcome").value(containsString("refund")))
        verify(orderId, "pay_6").andExpect(status().isConflict)
    }

    @Test
    fun `a failed payment marks the order failed, and residents can't start payments`() {
        val (orderId, _) = order()
        val body = """{"event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_7","order_id":"$orderId","amount":100,"error_description":"Card declined"}}}}"""
        webhook(body, "evt_6").andExpect(jsonPath("$.outcome").value("order failed"))
        call(resident, HttpMethod.POST, "/api/platform-invoices/$invoiceId/razorpay-order").andExpect(status().isForbidden)
    }
}
