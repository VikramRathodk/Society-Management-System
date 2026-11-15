package org.devvikram.societymanagement.subscription

import org.devvikram.societymanagement.auth.security.JwtService
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
            .andExpect(jsonPath("$.chargePerPeriod").value(1000.0)) // 3 × ₹20 is below the ₹1,000 minimum
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
    fun `a period costs per flat, never below the minimum`() {
        val plan = standard()
        assertEquals(BigDecimal("1000.00"), subscriptionService.charge(plan, BillingCycle.MONTHLY, 3))
        assertEquals(BigDecimal("1200.00"), subscriptionService.charge(plan, BillingCycle.MONTHLY, 60))
        assertEquals(BigDecimal("12000.00"), subscriptionService.charge(plan, BillingCycle.YEARLY, 3))
        assertEquals(BigDecimal("16000.00"), subscriptionService.charge(plan, BillingCycle.YEARLY, 80))
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
            """{"code":"tiny","name":"Tiny","pricePerFlatMonthly":5,"pricePerFlatYearly":50,"maxFlats":2}""",
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
        call(secretary, HttpMethod.POST, "/api/platform/plans", """{"code":"X","name":"X","pricePerFlatMonthly":1,"pricePerFlatYearly":10}""")
            .andExpect(status().isForbidden)
        call(superAdmin, HttpMethod.POST, "/api/platform/plans", """{"code":"STANDARD","name":"Again","pricePerFlatMonthly":1,"pricePerFlatYearly":10}""")
            .andExpect(status().isConflict)
        call(superAdmin, HttpMethod.PUT, "/api/platform/plans/${basic().id}", """{"code":"BASIC","name":"Basic","pricePerFlatMonthly":12,"pricePerFlatYearly":120,"minimumMonthly":500,"modules":["MAINTENANCE"],"active":false,"sortOrder":1}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pricePerFlatMonthly").value(12.0))
        call(secretary, HttpMethod.GET, "/api/plans")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.code == 'BASIC')]").isEmpty)
        call(superAdmin, HttpMethod.GET, "/api/plans").andExpect(jsonPath("$[?(@.code == 'BASIC')]").isNotEmpty)
    }
}
