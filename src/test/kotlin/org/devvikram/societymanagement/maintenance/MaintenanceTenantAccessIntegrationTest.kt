package org.devvikram.societymanagement.maintenance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.maintenance.service.MaintenanceNotificationListener
import org.devvikram.societymanagement.maintenance.service.MaintenancePaymentRecorded
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.ResidentType
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UpdateProfileCommand
import org.devvikram.societymanagement.user.service.UserService
import org.hamcrest.Matchers.hasSize
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
import kotlin.test.assertEquals

// Bills are the owner's: tenants see a flat's maintenance only when the society turns on
// tenantsSeeMaintenance. Same annotations and imported config as the other maintenance tests.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class MaintenanceTenantAccessIntegrationTest {

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
    private lateinit var owner: User
    private lateinit var family: User
    private lateinit var tenant: User
    private lateinit var secretary: User
    private var billId = 0L
    private var paymentId = 0L

    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, residentType: ResidentType? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode ${residentType ?: ""} user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(
                roleCode = roleCode,
                societyId = societyId,
                flatId = if (residentType != null) flatId else null,
                residentType = residentType,
            ),
        )
    )

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun getAs(user: User, path: String) = mockMvc.perform(get(path).header("Authorization", bearer(user)))

    private fun postAs(user: User, path: String, body: String): ResultActions = mockMvc.perform(
        post(path).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body)
    )

    private fun id(result: ResultActions, path: String = "$.id"): Long =
        JsonPath.read<Number>(result.andReturn().response.contentAsString, path).toLong()

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Tenant Access Society").id!!
        flatId = flatService.addFlat(wingService.addWing(societyId, "A").id!!, "101", BigDecimal("1000")).id!!
        owner = user("RESIDENT", ResidentType.OWNER)
        family = user("RESIDENT", ResidentType.FAMILY)
        tenant = user("RESIDENT", ResidentType.TENANT)
        secretary = user("SECRETARY")

        postAs(secretary, "/api/societies/$societyId/maintenance/charge-heads",
            """{"name":"Maintenance","calcType":"FIXED","amount":2000}""").andExpect(status().isCreated)
        val cycleId = id(postAs(secretary, "/api/societies/$societyId/maintenance/cycles",
            """{"periodStart":"2026-11-01","periodEnd":"2026-11-30","dueDate":"2026-11-15"}""").andExpect(status().isCreated))
        billId = id(getAs(secretary, "/api/maintenance/bills?societyId=$societyId&cycleId=$cycleId"), "$[0].id")
        paymentId = id(postAs(secretary, "/api/societies/$societyId/maintenance/payments",
            """{"flatId":$flatId,"amount":500,"paidOn":"$today","method":"CASH"}""").andExpect(status().isCreated))
    }

    private fun expectSees(user: User, sees: Boolean) {
        val count = if (sees) 1 else 0
        getAs(user, "/api/maintenance/bills?societyId=$societyId").andExpect(status().isOk).andExpect(jsonPath("$", hasSize<Any>(count)))
        getAs(user, "/api/maintenance/payments?societyId=$societyId").andExpect(status().isOk).andExpect(jsonPath("$", hasSize<Any>(count)))
        getAs(user, "/api/societies/$societyId/maintenance/dues").andExpect(status().isOk).andExpect(jsonPath("$", hasSize<Any>(count)))
        val detail = if (sees) status().isOk else status().isForbidden
        getAs(user, "/api/maintenance/bills/$billId").andExpect(detail)
        getAs(user, "/api/maintenance/payments/$paymentId").andExpect(detail)
    }

    @Test
    fun `by default only the owner side sees the flat's maintenance`() {
        expectSees(owner, true)
        expectSees(family, true)
        expectSees(tenant, false)
        getAs(tenant, "/api/maintenance/bills?societyId=$societyId&flatId=$flatId").andExpect(status().isForbidden)
    }

    @Test
    fun `tenants see it once the society turns the setting on`() {
        settingsService.update(societyId, mapOf(SocietySettingKeys.TENANTS_SEE_MAINTENANCE to true), secretary.id!!)
        expectSees(tenant, true)
        expectSees(owner, true)
    }

    @Test
    fun `maintenance pushes skip tenants unless the society includes them`() {
        userService.updateProfile(owner.id!!, UpdateProfileCommand(fcmToken = "owner-token"))
        userService.updateProfile(family.id!!, UpdateProfileCommand(fcmToken = "family-token"))
        userService.updateProfile(tenant.id!!, UpdateProfileCommand(fcmToken = "tenant-token"))
        val event = MaintenancePaymentRecorded(paymentId, societyId, flatId, "101", "R-000001", BigDecimal("500.00"))

        pushNotifier.sent.clear()
        notificationListener.onPaymentRecorded(event)
        assertEquals(setOf("owner-token", "family-token"), pushNotifier.sent.single().tokens.toSet())

        settingsService.update(societyId, mapOf(SocietySettingKeys.TENANTS_SEE_MAINTENANCE to true), secretary.id!!)
        pushNotifier.sent.clear()
        notificationListener.onPaymentRecorded(event)
        assertEquals(setOf("owner-token", "family-token", "tenant-token"), pushNotifier.sent.single().tokens.toSet())
    }
}
