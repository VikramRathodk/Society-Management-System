package org.devvikram.societymanagement.subscription

import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.common.exception.PaymentRequiredException
import org.devvikram.societymanagement.onboarding.service.StructureService
import org.devvikram.societymanagement.onboarding.service.WingPattern
import org.devvikram.societymanagement.subscription.repository.InvoiceRepository
import org.devvikram.societymanagement.subscription.service.InvoiceService
import org.junit.jupiter.api.assertThrows
import org.devvikram.societymanagement.society.model.SocietyStatus
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyProfileCommand
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.subscription.model.BillingCycle
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.subscription.repository.PlanRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionRepository
import org.devvikram.societymanagement.subscription.service.SubscriptionService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
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
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Plans and subscriptions with platform billing switched on. Real local Postgres, rolled back after each test.
@SpringBootTest(properties = ["app.billing.enabled=true", "app.billing.platform-gstin=27ABCDE1234F1Z5", "app.billing.state=Maharashtra"])
@AutoConfigureMockMvc
@Transactional
class SubscriptionIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var subscriptionService: SubscriptionService
    @Autowired lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired lateinit var planRepository: PlanRepository
    @Autowired lateinit var invoiceService: InvoiceService
    @Autowired lateinit var invoiceRepository: InvoiceRepository
    @Autowired lateinit var structureService: StructureService

    private var societyId = 0L
    private lateinit var superAdmin: User
    private lateinit var secretary: User
    private lateinit var resident: User
    private lateinit var committee: User

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
        // Only the seeded plans are on offer (a local database may keep plans a Postman run made).
        planRepository.findAll().filter { it.code !in setOf("FREE", "BASIC", "STANDARD", "PREMIUM") && it.active }.forEach {
            it.active = false
            planRepository.save(it)
        }
        societyId = societyService.createSociety("Billing Test Society").id!! // created live: trial starts
        val wing = wingService.addWing(societyId, "A").id!!
        val flat = flatService.addFlat(wing, "101").id!!
        flatService.addFlat(wing, "102")
        flatService.addFlat(wing, "103")
        superAdmin = user("SUPER_ADMIN", null)
        secretary = user("SECRETARY", societyId)
        committee = user("COMMITTEE", societyId)
        resident = user("RESIDENT", societyId, flat)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun call(user: User, method: HttpMethod, path: String, body: String? = null): ResultActions {
        val req = request(method, path).header("Authorization", bearer(user))
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(req)
    }

    private fun standard() = planRepository.findByCode("STANDARD")!!
    private fun basic() = planRepository.findByCode("BASIC")!!
    private fun free() = planRepository.findByCode("FREE")!!

    @Test
    fun `the plans on offer are public, without sign-in, and only active ones`() {
        val hidden = basic()
        hidden.active = false
        planRepository.save(hidden)
        mockMvc.perform(request(HttpMethod.GET, "/api/public/plans"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.code == 'STANDARD')]").exists())
            .andExpect(jsonPath("$[?(@.code == 'BASIC')]").doesNotExist())
        // Only reading is public.
        mockMvc.perform(request(HttpMethod.POST, "/api/public/plans"))
            .andExpect(status().isUnauthorized)
    }

    // ---- Starting ----

    @Test
    fun `a society that is created live starts a 30-day trial on the default plan`() {
        val s = subscriptionRepository.findBySociety(societyId)!!
        assertEquals(SubscriptionStatus.TRIAL, s.status)
        assertEquals(standard().id, s.planId)
        assertEquals(subscriptionService.today().plusDays(29), s.trialEndsOn)
        assertEquals(subscriptionService.today().plusDays(30), s.nextInvoiceOn)
        call(secretary, HttpMethod.GET, "/api/societies/$societyId/subscription")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("TRIAL"))
            .andExpect(jsonPath("$.plan.code").value("STANDARD"))
            .andExpect(jsonPath("$.flats").value(3))
            .andExpect(jsonPath("$.chargePerPeriod").value(699.0)) // the plan's price covers 50 flats
            .andExpect(jsonPath("$.extraFlats").value(0))
            .andExpect(jsonPath("$.events[0].kind").value("STARTED"))
    }

    @Test
    fun `a society in setup starts its trial when it goes live`() {
        val setup = societyService.createSociety("Setup Society", status = SocietyStatus.SETUP).id!!
        assertNull(subscriptionRepository.findBySociety(setup))
        societyService.updateProfile(
            setup,
            SocietyProfileCommand(address = "1 Road", city = "Pune", state = "Maharashtra", pincode = "411001", contactPhone = "9876543210"),
        )
        societyService.goLive(setup, secretary.id!!, Instant.now())
        assertNotNull(subscriptionRepository.findBySociety(setup))
        call(superAdmin, HttpMethod.POST, "/api/societies/$setup/subscription").andExpect(status().isConflict)
    }

    @Test
    fun `start-all gives every live society without one its trial`() {
        val first = subscriptionRepository.findBySociety(societyId)!!
        subscriptionRepository.save(first) // already has one
        call(secretary, HttpMethod.POST, "/api/platform/subscriptions/start-all").andExpect(status().isForbidden)
        call(superAdmin, HttpMethod.POST, "/api/platform/subscriptions/start-all")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.started").isNumber)
        assertNull(subscriptionService.all().firstOrNull { it.subscription.societyId == societyId && it.subscription.status != SubscriptionStatus.TRIAL })
    }

    // ---- Charges ----

    @Test
    fun `a period costs the plan's price, plus each flat beyond the included ones`() {
        val plan = standard() // ₹699 / ₹6,990 for 50 flats, ₹12 / ₹120 per extra flat
        assertEquals(BigDecimal("699.00"), subscriptionService.charge(plan, BillingCycle.MONTHLY, 3))
        assertEquals(BigDecimal("699.00"), subscriptionService.charge(plan, BillingCycle.MONTHLY, 50))
        assertEquals(BigDecimal("819.00"), subscriptionService.charge(plan, BillingCycle.MONTHLY, 60))
        assertEquals(BigDecimal("6990.00"), subscriptionService.charge(plan, BillingCycle.YEARLY, 3))
        assertEquals(BigDecimal("10590.00"), subscriptionService.charge(plan, BillingCycle.YEARLY, 80))
        assertEquals(BigDecimal("0.00"), subscriptionService.charge(free(), BillingCycle.MONTHLY, 15))
    }

    // ---- Featured and recommended ----

    @Test
    fun `the quote for a flat count is public, with every plan's total and the recommended one`() {
        fun quote(flats: Int) = mockMvc.perform(request(HttpMethod.GET, "/api/public/plans/quote?flats=$flats")).andExpect(status().isOk)
        quote(10)
            .andExpect(jsonPath("$.recommendedPlanId").value(free().id!!))
            .andExpect(jsonPath("$.quotes[?(@.plan.code == 'STANDARD')].plan.featured").value(true))
        quote(20).andExpect(jsonPath("$.recommendedPlanId").value(basic().id!!))
        quote(58)
            .andExpect(jsonPath("$.recommendedPlanId").value(standard().id!!))
            .andExpect(jsonPath("$.quotes[?(@.plan.code == 'STANDARD')].monthly").value(795.0)) // 699 + 8 × 12
            .andExpect(jsonPath("$.quotes[?(@.plan.code == 'STANDARD')].extraFlats").value(8))
            .andExpect(jsonPath("$.quotes[?(@.plan.code == 'STANDARD')].recommended").value(true))
            .andExpect(jsonPath("$.quotes[?(@.plan.code == 'FREE')].fits").value(false))
        quote(200).andExpect(jsonPath("$.recommendedPlanId").value(planRepository.findByCode("PREMIUM")!!.id!!))
        mockMvc.perform(request(HttpMethod.GET, "/api/public/plans/quote?flats=0")).andExpect(status().isBadRequest)
    }

    @Test
    fun `the society sees the recommendation for its own flats`() {
        call(secretary, HttpMethod.GET, "/api/societies/$societyId/subscription/recommendation")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.flats").value(3))
            .andExpect(jsonPath("$.recommendedPlanId").value(free().id!!))
        call(resident, HttpMethod.GET, "/api/societies/$societyId/subscription/recommendation").andExpect(status().isForbidden)
    }

    @Test
    fun `only one plan is featured at a time`() {
        call(
            superAdmin, HttpMethod.PUT, "/api/platform/plans/${basic().id}",
            """{"code":"BASIC","name":"Basic","priceMonthly":499,"priceYearly":4990,"includedFlats":30,"extraFlatMonthly":10,"extraFlatYearly":100,"modules":["MAINTENANCE"],"sortOrder":1,"featured":true,"recommendedFromFlats":16}""",
        ).andExpect(status().isOk).andExpect(jsonPath("$.featured").value(true))
        assertEquals(listOf("BASIC"), planRepository.findAll().filter { it.featured }.map { it.code })
    }

    @Test
    fun `extra flats must cost something unless the plan stops at its included flats`() {
        call(superAdmin, HttpMethod.POST, "/api/platform/plans", """{"code":"LEAKY","name":"Leaky","priceMonthly":100,"priceYearly":1000,"includedFlats":10}""")
            .andExpect(status().isBadRequest)
        call(superAdmin, HttpMethod.POST, "/api/platform/plans", """{"code":"CAPPED","name":"Capped","priceMonthly":100,"priceYearly":1000,"includedFlats":10,"maxFlats":10}""")
            .andExpect(status().isCreated)
    }

    // ---- Flat limit ----

    @Test
    fun `the free plan stops at its flat limit, one at a time or in bulk`() {
        call(superAdmin, HttpMethod.PATCH, "/api/societies/$societyId/subscription/plan", """{"planId":${free().id}}""").andExpect(status().isOk)
        val wing = wingService.addWing(societyId, "B").id!!
        (1..12).forEach { flatService.addFlat(wing, "B$it") } // 3 + 12 = 15, the limit
        call(secretary, HttpMethod.POST, "/api/wings/$wing/flats", """{"flatNumber":"B13"}""")
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("up to 15 flats")))
        assertThrows<PaymentRequiredException> {
            structureService.generate(societyId, listOf(WingPattern(wing = "C", floorsFrom = 1, floorsTo = 1, flatsPerFloor = 2)))
        }
        assertEquals(15, subscriptionService.flatCount(societyId))
        // A bigger plan lifts the limit.
        call(superAdmin, HttpMethod.PATCH, "/api/societies/$societyId/subscription/plan", """{"planId":${basic().id}}""").andExpect(status().isOk)
        flatService.addFlat(wing, "B13")
    }

    @Test
    fun `a period on the free plan issues no invoice and the subscription moves on`() {
        call(superAdmin, HttpMethod.PATCH, "/api/societies/$societyId/subscription/plan", """{"planId":${free().id}}""").andExpect(status().isOk)
        val next = subscriptionRepository.findBySociety(societyId)!!.nextInvoiceOn
        assertNull(invoiceService.issueDue(societyId, superAdmin.id, next.plusMonths(1)))
        val s = subscriptionRepository.findBySociety(societyId)!!
        assertEquals(SubscriptionStatus.ACTIVE, s.status)
        assertEquals(next.plusMonths(2), s.nextInvoiceOn) // two free periods passed
        assertEquals(0, invoiceRepository.findBySociety(societyId).size)
    }

    // ---- Changes ----

    @Test
    fun `during a trial a plan change applies at once, afterwards from the next period`() {
        call(superAdmin, HttpMethod.PATCH, "/api/societies/$societyId/subscription/plan", """{"planId":${basic().id},"cycle":"YEARLY"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plan.code").value("BASIC"))
            .andExpect(jsonPath("$.cycle").value("YEARLY"))
            .andExpect(jsonPath("$.pendingPlan").doesNotExist())

        val s = subscriptionRepository.findBySociety(societyId)!!
        s.status = SubscriptionStatus.ACTIVE
        subscriptionRepository.save(s)
        call(superAdmin, HttpMethod.PATCH, "/api/societies/$societyId/subscription/plan", """{"planId":${standard().id}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plan.code").value("BASIC"))
            .andExpect(jsonPath("$.pendingPlan.code").value("STANDARD"))
            .andExpect(jsonPath("$.events[*].kind", org.hamcrest.Matchers.hasItem("PLAN_CHANGE_SCHEDULED")))
        call(secretary, HttpMethod.PATCH, "/api/societies/$societyId/subscription/plan", """{"planId":${basic().id}}""").andExpect(status().isForbidden)
    }

    @Test
    fun `a plan with fewer flats than the society has is refused`() {
        val small = call(
            superAdmin, HttpMethod.POST, "/api/platform/plans",
            """{"code":"tiny","name":"Tiny","priceMonthly":5,"priceYearly":50,"maxFlats":2}""",
        ).andExpect(status().isCreated).andExpect(jsonPath("$.code").value("TINY"))
            .andReturn().response.contentAsString
        val id = com.jayway.jsonpath.JsonPath.read<Number>(small, "$.id").toLong()
        call(superAdmin, HttpMethod.PATCH, "/api/societies/$societyId/subscription/plan", """{"planId":$id}""").andExpect(status().isBadRequest)
    }

    @Test
    fun `discount, billing contact, change request and cancelling`() {
        call(superAdmin, HttpMethod.PUT, "/api/societies/$societyId/subscription/discount", """{"percent":20,"periods":6,"note":"Founding society"}""")
            .andExpect(jsonPath("$.discountPercent").value(20))
            .andExpect(jsonPath("$.discountPeriodsLeft").value(6))
        call(superAdmin, HttpMethod.PUT, "/api/societies/$societyId/subscription/discount", """{"percent":120,"periods":1}""").andExpect(status().isBadRequest)

        call(secretary, HttpMethod.PUT, "/api/societies/$societyId/subscription/billing-contact", """{"userId":${committee.id}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.billingContactId").value(committee.id!!))
        call(secretary, HttpMethod.PUT, "/api/societies/$societyId/subscription/billing-contact", """{"userId":${resident.id}}""").andExpect(status().isBadRequest)
        call(committee, HttpMethod.PUT, "/api/societies/$societyId/subscription/billing-contact", """{"userId":${committee.id}}""").andExpect(status().isForbidden)

        call(secretary, HttpMethod.POST, "/api/societies/$societyId/subscription/request-change", """{"planId":${basic().id},"note":"We only need maintenance"}""")
            .andExpect(jsonPath("$.events[*].kind", org.hamcrest.Matchers.hasItem("CHANGE_REQUESTED")))
            .andExpect(jsonPath("$.plan.code").value("STANDARD")) // nothing changes until the platform does it

        call(resident, HttpMethod.GET, "/api/societies/$societyId/subscription").andExpect(status().isForbidden)
        call(superAdmin, HttpMethod.POST, "/api/societies/$societyId/subscription/cancel", """{"note":"Society left"}""")
            .andExpect(jsonPath("$.status").value("CANCELLED"))
        call(superAdmin, HttpMethod.PATCH, "/api/societies/$societyId/subscription/plan", """{"cycle":"YEARLY"}""").andExpect(status().isConflict)
    }

    // ---- Plans ----

    @Test
    fun `plans are managed by the platform, societies see the active ones`() {
        call(secretary, HttpMethod.POST, "/api/platform/plans", """{"code":"X","name":"X","priceMonthly":1,"priceYearly":10}""")
            .andExpect(status().isForbidden)
        call(superAdmin, HttpMethod.POST, "/api/platform/plans", """{"code":"STANDARD","name":"Again","priceMonthly":1,"priceYearly":10}""")
            .andExpect(status().isConflict)
        call(superAdmin, HttpMethod.PUT, "/api/platform/plans/${basic().id}", """{"code":"BASIC","name":"Basic","priceMonthly":549,"priceYearly":5490,"includedFlats":30,"extraFlatMonthly":10,"extraFlatYearly":100,"modules":["MAINTENANCE"],"active":false,"sortOrder":1}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.priceMonthly").value(549.0))
            .andExpect(jsonPath("$.includedFlats").value(30))
        call(secretary, HttpMethod.GET, "/api/plans")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.code == 'BASIC')]").isEmpty)
        call(superAdmin, HttpMethod.GET, "/api/plans").andExpect(jsonPath("$[?(@.code == 'BASIC')]").isNotEmpty)
    }
}
