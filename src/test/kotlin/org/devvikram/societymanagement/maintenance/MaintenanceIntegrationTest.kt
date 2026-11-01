package org.devvikram.societymanagement.maintenance

import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.maintenance.service.IssuedBill
import org.devvikram.societymanagement.maintenance.service.MaintenanceBillsIssued
import org.devvikram.societymanagement.maintenance.service.MaintenanceNotificationListener
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import com.jayway.jsonpath.JsonPath
import java.math.BigDecimal
import java.time.LocalDate

// Maintenance billing end to end through the controllers. Same setup as ComplaintIntegrationTest:
// real local Postgres with seeded roles, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class MaintenanceIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var notificationListener: MaintenanceNotificationListener
    @Autowired lateinit var pushNotifier: RecordingPushNotifier
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService

    private var societyId = 0L
    private var flatId = 0L
    private var otherFlatId = 0L
    private lateinit var resident: User
    private lateinit var neighbour: User
    private lateinit var guard: User
    private lateinit var committee: User
    private lateinit var secretary: User
    private lateinit var outsideSecretary: User

    private val november = """{"periodStart":"2026-11-01","periodEnd":"2026-11-30","dueDate":"2026-11-15"}"""

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
        societyId = societyService.createSociety("Maintenance Test Society").id!!
        val wing = wingService.addWing(societyId, "A")
        flatId = flatService.addFlat(wing.id!!, "101", BigDecimal("850")).id!!
        otherFlatId = flatService.addFlat(wing.id!!, "102", BigDecimal("1200")).id!!
        resident = user("RESIDENT", flatId = flatId)
        neighbour = user("RESIDENT", flatId = otherFlatId)
        guard = user("GUARD")
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")
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

    private fun addHead(name: String, calcType: String, amount: String, by: User = secretary) =
        sendAs(by, "POST", "/api/societies/$societyId/maintenance/charge-heads",
            """{"name":"$name","calcType":"$calcType","amount":$amount}""")

    private fun idOf(result: ResultActions): Long = read(result, "$.id")

    private fun read(result: ResultActions, path: String): Long =
        JsonPath.read<Number>(result.andReturn().response.contentAsString, path).toLong()

    private fun issueNovember(): Long {
        addHead("Maintenance", "PER_SQFT", "2.50").andExpect(status().isCreated)
        addHead("Sinking fund", "FIXED", "500").andExpect(status().isCreated)
        return idOf(sendAs(secretary, "POST", "/api/societies/$societyId/maintenance/cycles", november).andExpect(status().isCreated))
    }

    private fun billIdOf(cycleId: Long, flat: Long): Long {
        return read(getAs(secretary, "/api/maintenance/bills?societyId=$societyId&cycleId=$cycleId&flatId=$flat"), "$[0].id")
    }

    @Test
    fun `everyone in the society reads the configuration, only the secretary changes it`() {
        addHead("Maintenance", "FIXED", "2000").andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Maintenance"))
            .andExpect(jsonPath("$.active").value(true))

        getAs(resident, "/api/societies/$societyId/maintenance/charge-heads")
            .andExpect(status().isOk).andExpect(jsonPath("$", hasSize<Any>(1)))
        getAs(resident, "/api/societies/$societyId/maintenance/settings")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.billingFrequency").value("MONTHLY"))
            .andExpect(jsonPath("$.dueDays").value(15))

        addHead("Parking", "FIXED", "300", by = resident).andExpect(status().isForbidden)
        addHead("Parking", "FIXED", "300", by = committee).andExpect(status().isForbidden)
        addHead("Parking", "FIXED", "300", by = outsideSecretary).andExpect(status().isForbidden)
        getAs(outsideSecretary, "/api/societies/$societyId/maintenance/charge-heads").andExpect(status().isForbidden)
    }

    @Test
    fun `settings and charge heads can be changed and head names are unique`() {
        sendAs(secretary, "PUT", "/api/societies/$societyId/maintenance/settings",
            """{"billingFrequency":"QUARTERLY","dueDays":10,"roundToRupee":false}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.billingFrequency").value("QUARTERLY"))
            .andExpect(jsonPath("$.updatedByName").value("SECRETARY user"))

        val headId = idOf(addHead("Maintenance", "FIXED", "2000"))
        addHead("maintenance", "FIXED", "1").andExpect(status().isBadRequest)
        addHead("Bad", "FIXED", "-1").andExpect(status().isBadRequest)

        sendAs(secretary, "PATCH", "/api/maintenance/charge-heads/$headId", """{"amount":2100.50,"active":false}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.amount").value(2100.50))
            .andExpect(jsonPath("$.active").value(false))
        sendAs(resident, "DELETE", "/api/maintenance/charge-heads/$headId").andExpect(status().isForbidden)
        sendAs(secretary, "DELETE", "/api/maintenance/charge-heads/$headId").andExpect(status().isNoContent)
    }

    @Test
    fun `preview lists every flat's bill and flags flats that cannot be billed`() {
        addHead("Maintenance", "PER_SQFT", "2.50")
        addHead("Sinking fund", "FIXED", "500")
        val wing = wingService.listBySociety(societyId).single()
        flatService.addFlat(wing.id!!, "103")

        sendAs(secretary, "POST", "/api/societies/$societyId/maintenance/cycles/preview", november)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.billCount").value(2))
            .andExpect(jsonPath("$.total").value(6125.00))
            .andExpect(jsonPath("$.bills[0].flatNumber").value("101"))
            .andExpect(jsonPath("$.bills[0].lines", hasSize<Any>(2)))
            .andExpect(jsonPath("$.problems[0].flatNumber").value("103"))

        sendAs(secretary, "POST", "/api/societies/$societyId/maintenance/cycles", november)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("A-103: No area set")))
    }

    @Test
    fun `issuing needs a society-wide billing role and an active charge head`() {
        sendAs(secretary, "POST", "/api/societies/$societyId/maintenance/cycles", november)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("active charge head")))
        addHead("Maintenance", "FIXED", "2000")

        for (who in listOf(resident, guard, committee, outsideSecretary)) {
            sendAs(who, "POST", "/api/societies/$societyId/maintenance/cycles", november).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `issuing bills every flat once, and an overlapping period is refused`() {
        val cycleId = issueNovember()

        getAs(committee, "/api/maintenance/cycles/$cycleId")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ISSUED"))
            .andExpect(jsonPath("$.billCount").value(2))
            .andExpect(jsonPath("$.totalBilled").value(6125.00))
            .andExpect(jsonPath("$.billsByStatus.UNPAID").value(2))

        getAs(secretary, "/api/maintenance/bills?societyId=$societyId&cycleId=$cycleId")
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].billNumber").value("202611-0001"))
            .andExpect(jsonPath("$[0].total").value(2625.00))
            .andExpect(jsonPath("$[1].billNumber").value("202611-0002"))

        sendAs(secretary, "POST", "/api/societies/$societyId/maintenance/cycles",
            """{"periodStart":"2026-11-15","periodEnd":"2026-12-14","dueDate":"2026-11-30"}""")
            .andExpect(status().isConflict)

        getAs(secretary, "/api/societies/$societyId/maintenance/cycles/next")
            .andExpect(jsonPath("$.periodStart").value("2026-12-01"))
            .andExpect(jsonPath("$.periodEnd").value("2026-12-31"))
            .andExpect(jsonPath("$.dueDate").value("2026-12-16"))
    }

    @Test
    fun `issued bills keep their charges when the configuration changes`() {
        val cycleId = issueNovember()
        // Heads are listed by sort order then name: Maintenance, Sinking fund.
        val headId = read(getAs(secretary, "/api/societies/$societyId/maintenance/charge-heads"), "$[1].id")
        sendAs(secretary, "PATCH", "/api/maintenance/charge-heads/$headId", """{"amount":900}""")

        getAs(resident, "/api/maintenance/bills/${billIdOf(cycleId, flatId)}")
            .andExpect(jsonPath("$.total").value(2625.00))
            .andExpect(jsonPath("$.lines[1].headName").value("Sinking fund"))
            .andExpect(jsonPath("$.lines[1].amount").value(500.00))
    }

    @Test
    fun `residents see only their own flat's bills`() {
        val cycleId = issueNovember()
        val ownBill = billIdOf(cycleId, flatId)
        val neighbourBill = billIdOf(cycleId, otherFlatId)

        getAs(resident, "/api/maintenance/bills?societyId=$societyId")
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].flatNumber").value("101"))
        getAs(resident, "/api/maintenance/bills/$ownBill")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lines[0].headName").value("Maintenance"))
            .andExpect(jsonPath("$.lines[0].quantity").value(850.00))
        getAs(resident, "/api/maintenance/bills/$neighbourBill").andExpect(status().isForbidden)
        getAs(resident, "/api/maintenance/bills?societyId=$societyId&flatId=$otherFlatId").andExpect(status().isForbidden)
        getAs(resident, "/api/maintenance/cycles/$cycleId").andExpect(status().isForbidden)
        getAs(guard, "/api/maintenance/bills?societyId=$societyId").andExpect(status().isForbidden)
        getAs(committee, "/api/maintenance/bills/$neighbourBill").andExpect(status().isOk)
    }

    @Test
    fun `a waived bill needs a reason and can be waived once`() {
        val bill = billIdOf(issueNovember(), flatId)

        sendAs(secretary, "POST", "/api/maintenance/bills/$bill/waive", """{"reason":" "}""").andExpect(status().isBadRequest)
        sendAs(committee, "POST", "/api/maintenance/bills/$bill/waive", """{"reason":"Hardship"}""").andExpect(status().isForbidden)
        sendAs(secretary, "POST", "/api/maintenance/bills/$bill/waive", """{"reason":"Hardship"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("WAIVED"))
            .andExpect(jsonPath("$.statusReason").value("Hardship"))
            .andExpect(jsonPath("$.statusChangedByName").value("SECRETARY user"))
        sendAs(secretary, "POST", "/api/maintenance/bills/$bill/waive", """{"reason":"Again"}""").andExpect(status().isConflict)
    }

    @Test
    fun `cancelling a cycle cancels its bills and frees the period`() {
        val cycleId = issueNovember()

        sendAs(secretary, "POST", "/api/maintenance/cycles/$cycleId/cancel", """{"reason":"Wrong rate"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelReason").value("Wrong rate"))
            .andExpect(jsonPath("$.billsByStatus.CANCELLED").value(2))
        sendAs(secretary, "POST", "/api/maintenance/cycles/$cycleId/cancel", """{"reason":"Again"}""").andExpect(status().isConflict)

        sendAs(secretary, "POST", "/api/societies/$societyId/maintenance/cycles", november).andExpect(status().isCreated)
        getAs(secretary, "/api/societies/$societyId/maintenance/cycles")
            .andExpect(jsonPath("$", hasSize<Any>(2)))
    }

    @Test
    fun `the committee exports a cycle as CSV`() {
        val cycleId = issueNovember()

        getAs(committee, "/api/maintenance/cycles/$cycleId/export")
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", containsString("maintenance-bills-$societyId-2026-11-01")))
            .andExpect(content().string(startsWith("bill_number,wing,flat,period_start")))
            .andExpect(content().string(containsString("202611-0001,A,101,2026-11-01,2026-11-30,2026-11-15,2625.00,0.00,2625.00,UNPAID")))
        getAs(resident, "/api/maintenance/cycles/$cycleId/export").andExpect(status().isForbidden)
    }

    @Test
    fun `flats can be created and updated with an area`() {
        val wingId = wingService.listBySociety(societyId).single().id!!
        val created = sendAs(secretary, "POST", "/api/wings/$wingId/flats", """{"flatNumber":"201","areaSqft":975.5}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.areaSqft").value(975.5))
        val id = idOf(created)
        sendAs(secretary, "PATCH", "/api/flats/$id", """{"flatNumber":"201A"}""")
            .andExpect(jsonPath("$.areaSqft").value(975.5))
        sendAs(secretary, "PATCH", "/api/flats/$id", """{"flatNumber":"201A","areaSqft":0}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `issuing tells each flat's residents about their own bill`() {
        deviceOf(resident, "resident-token")
        deviceOf(neighbour, "neighbour-token")
        deviceOf(committee, "committee-token")
        pushNotifier.sent.clear()

        notificationListener.onIssued(
            MaintenanceBillsIssued(
                cycleId = 1L,
                societyId = societyId,
                periodStart = LocalDate.of(2026, 11, 1),
                periodEnd = LocalDate.of(2026, 11, 30),
                dueDate = LocalDate.of(2026, 11, 15),
                bills = listOf(
                    IssuedBill(10L, flatId, "101", BigDecimal("2625.00")),
                    IssuedBill(11L, otherFlatId, "102", BigDecimal("3500.00")),
                ),
            )
        )

        val byToken = pushNotifier.sent.associateBy { it.tokens.single() }
        assertEquals(setOf("resident-token", "neighbour-token"), byToken.keys)
        assertEquals("November 2026 bill for flat 101: ₹2,625.00, due 15 Nov 2026", byToken.getValue("resident-token").body)
        assertEquals("MAINTENANCE_BILL_ISSUED", byToken.getValue("neighbour-token").data["type"])
        assertEquals("11", byToken.getValue("neighbour-token").data["billId"])
    }

    private fun deviceOf(user: User, token: String): User =
        userService.updateProfile(user.id!!, UpdateProfileCommand(fcmToken = token))

}
