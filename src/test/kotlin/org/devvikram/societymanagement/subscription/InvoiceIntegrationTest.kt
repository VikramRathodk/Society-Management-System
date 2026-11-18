package org.devvikram.societymanagement.subscription

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyProfileCommand
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.subscription.repository.InvoiceRepository
import org.devvikram.societymanagement.subscription.repository.PlanRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionRepository
import org.devvikram.societymanagement.subscription.service.SubscriptionService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

// Invoices with GST, payments and credit notes. The platform's GSTIN is in Maharashtra for these tests.
@SpringBootTest(properties = ["app.billing.enabled=true", "app.billing.platform-gstin=27ABCDE1234F1Z5", "app.billing.state=Maharashtra"])
@AutoConfigureMockMvc
@Transactional
class InvoiceIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var subscriptionService: SubscriptionService
    @Autowired lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired lateinit var invoiceRepository: InvoiceRepository
    @Autowired lateinit var planRepository: PlanRepository
    @Autowired lateinit var invoiceService: org.devvikram.societymanagement.subscription.service.InvoiceService

    private var societyId = 0L
    private lateinit var superAdmin: User
    private lateinit var secretary: User
    private lateinit var resident: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(role: String, society: Long?, flatId: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$role user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = role, societyId = society, flatId = flatId),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = society("Maharashtra")
        superAdmin = user("SUPER_ADMIN", null)
        secretary = user("SECRETARY", societyId)
        val flat = flatService.listByWing(wingService.listBySociety(societyId).single().id!!).first().id!!
        resident = user("RESIDENT", societyId, flat)
    }

    // A live society with three flats in the given state, its trial over (first invoice due today).
    private fun society(state: String): Long {
        val id = societyService.createSociety("Invoice Test Society $state").id!!
        societyService.updateProfile(id, SocietyProfileCommand(address = "1 Road", city = "City", state = state, pincode = "411001"))
        val wing = wingService.addWing(id, "A").id!!
        listOf("101", "102", "103").forEach { flatService.addFlat(wing, it) }
        dueToday(id)
        return id
    }

    private fun dueToday(id: Long) {
        val s = subscriptionRepository.findBySociety(id)!!
        s.nextInvoiceOn = subscriptionService.today()
        subscriptionRepository.save(s)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun call(user: User, method: HttpMethod, path: String, body: String? = null): ResultActions {
        val req = request(method, path).header("Authorization", bearer(user))
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(req)
    }

    private fun issue(society: Long = societyId): Long {
        val json = call(superAdmin, HttpMethod.POST, "/api/societies/$society/subscription/invoices/issue-due")
            .andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read<Number>(json, "$.summary.id").toLong()
    }

    @Test
    fun `nothing is invoiced before the next period starts`() {
        val s = subscriptionRepository.findBySociety(societyId)!!
        s.nextInvoiceOn = subscriptionService.today().plusDays(3)
        subscriptionRepository.save(s)
        call(superAdmin, HttpMethod.POST, "/api/societies/$societyId/subscription/invoices/issue-due").andExpect(status().isNoContent)
    }

    @Test
    fun `the first invoice ends the trial, with CGST and SGST in the platform's state`() {
        val today = subscriptionService.today()
        call(superAdmin, HttpMethod.POST, "/api/societies/$societyId/subscription/invoices/issue-due")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.summary.number").value(matchesPattern("SAH/\\d{4}-\\d{2}/\\d{4}")))
            .andExpect(jsonPath("$.summary.periodStart").value(today.toString()))
            .andExpect(jsonPath("$.summary.periodEnd").value(today.plusMonths(1).minusDays(1).toString()))
            .andExpect(jsonPath("$.flats").value(3))
            .andExpect(jsonPath("$.amount").value(699.0)) // the plan's price covers 50 flats; the society has 3
            .andExpect(jsonPath("$.cgst").value(62.91))
            .andExpect(jsonPath("$.sgst").value(62.91))
            .andExpect(jsonPath("$.igst").value(0.0))
            .andExpect(jsonPath("$.total").value(824.82))
            .andExpect(jsonPath("$.summary.dueOn").value(today.plusDays(15).toString()))
            .andExpect(jsonPath("$.seller.gstin").value("27ABCDE1234F1Z5"))
        val s = subscriptionRepository.findBySociety(societyId)!!
        assertEquals(SubscriptionStatus.ACTIVE, s.status)
        assertEquals(today.plusMonths(1), s.nextInvoiceOn)
        // The next period isn't due yet.
        call(superAdmin, HttpMethod.POST, "/api/societies/$societyId/subscription/invoices/issue-due").andExpect(status().isNoContent)
    }

    @Test
    fun `another state pays IGST`() {
        val other = society("Karnataka")
        val id = issue(other)
        call(superAdmin, HttpMethod.GET, "/api/platform-invoices/$id")
            .andExpect(jsonPath("$.cgst").value(0.0))
            .andExpect(jsonPath("$.igst").value(125.82))
            .andExpect(jsonPath("$.total").value(824.82))
    }

    @Test
    fun `a discount applies for its periods, a pending plan from the next invoice`() {
        call(superAdmin, HttpMethod.PUT, "/api/societies/$societyId/subscription/discount", """{"percent":20,"periods":1}""")
        val first = issue()
        call(superAdmin, HttpMethod.GET, "/api/platform-invoices/$first")
            .andExpect(jsonPath("$.discountAmount").value(139.8))
            .andExpect(jsonPath("$.taxableValue").value(559.2))
            .andExpect(jsonPath("$.total").value(659.86))

        val basic = planRepository.findByCode("BASIC")!!
        call(superAdmin, HttpMethod.PATCH, "/api/societies/$societyId/subscription/plan", """{"planId":${basic.id}}""")
            .andExpect(jsonPath("$.pendingPlan.code").value("BASIC"))
        // A month later: issue as of the next period's first day.
        val next = subscriptionRepository.findBySociety(societyId)!!.nextInvoiceOn
        val second = invoiceService.issueDue(societyId, superAdmin.id, next)!!.id!!
        call(superAdmin, HttpMethod.GET, "/api/platform-invoices/$second")
            .andExpect(jsonPath("$.planCode").value("BASIC"))
            .andExpect(jsonPath("$.discountAmount").value(0.0))
            .andExpect(jsonPath("$.amount").value(499.0))
            .andExpect(jsonPath("$.total").value(588.82))
        call(secretary, HttpMethod.GET, "/api/societies/$societyId/subscription")
            .andExpect(jsonPath("$.plan.code").value("BASIC"))
            .andExpect(jsonPath("$.pendingPlan").doesNotExist())
    }

    @Test
    fun `payments and credit notes settle an invoice, with receipts`() {
        val id = issue()
        call(superAdmin, HttpMethod.POST, "/api/platform-invoices/$id/payments", """{"amount":500,"mode":"UPI","reference":"UTR123"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.summary.status").value("PARTLY_PAID"))
            .andExpect(jsonPath("$.summary.balance").value(324.82))
            .andExpect(jsonPath("$.payments[0].receiptNumber").value(matchesPattern("SAH-R/\\d{4}-\\d{2}/\\d{4}")))
        call(superAdmin, HttpMethod.POST, "/api/platform-invoices/$id/payments", """{"amount":700,"mode":"UPI"}""").andExpect(status().isBadRequest)
        call(superAdmin, HttpMethod.POST, "/api/platform-invoices/$id/credit-notes", """{"amount":180,"reason":"Tax charged twice"}""")
            .andExpect(jsonPath("$.creditNotes[0].number").value(matchesPattern("SAH-CN/\\d{4}-\\d{2}/\\d{4}")))
            .andExpect(jsonPath("$.summary.balance").value(144.82))
        call(superAdmin, HttpMethod.POST, "/api/platform-invoices/$id/payments", """{"amount":144.82,"mode":"BANK_TRANSFER"}""")
            .andExpect(jsonPath("$.summary.status").value("PAID"))
            .andExpect(jsonPath("$.summary.balance").value(0.0))
        call(superAdmin, HttpMethod.POST, "/api/platform-invoices/$id/payments", """{"amount":1,"mode":"CASH"}""").andExpect(status().isConflict)
    }

    @Test
    fun `paying an overdue society's invoices makes it active again`() {
        val id = issue()
        val invoice = invoiceRepository.findById(id)!!
        invoice.dueOn = subscriptionService.today().minusDays(1)
        invoiceRepository.save(invoice)
        val s = subscriptionRepository.findBySociety(societyId)!!
        s.status = SubscriptionStatus.PAST_DUE
        subscriptionRepository.save(s)
        call(superAdmin, HttpMethod.GET, "/api/platform/invoices?overdue=true")
            .andExpect(jsonPath("$[?(@.id == $id)]").isNotEmpty)
        call(superAdmin, HttpMethod.POST, "/api/platform-invoices/$id/payments", """{"amount":824.82,"mode":"CHEQUE","reference":"000123"}""")
        assertEquals(SubscriptionStatus.ACTIVE, subscriptionRepository.findBySociety(societyId)!!.status)
    }

    @Test
    fun `the society sees its invoices, only the platform records payments`() {
        val id = issue()
        call(secretary, HttpMethod.GET, "/api/societies/$societyId/subscription/invoices")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(id))
        call(secretary, HttpMethod.GET, "/api/platform-invoices/$id").andExpect(status().isOk)
        call(resident, HttpMethod.GET, "/api/platform-invoices/$id").andExpect(status().isForbidden)
        call(secretary, HttpMethod.POST, "/api/platform-invoices/$id/payments", """{"amount":1,"mode":"UPI"}""").andExpect(status().isForbidden)
        call(secretary, HttpMethod.POST, "/api/societies/$societyId/subscription/invoices/issue-due").andExpect(status().isForbidden)
        call(secretary, HttpMethod.GET, "/api/platform/invoices").andExpect(status().isForbidden)
    }
}
