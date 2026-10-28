package org.devvikram.societymanagement.complaint

import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.complaint.model.ComplaintCategory
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import org.devvikram.societymanagement.complaint.service.ComplaintNotificationListener
import org.devvikram.societymanagement.complaint.service.ComplaintRaised
import org.devvikram.societymanagement.complaint.service.ComplaintService
import org.devvikram.societymanagement.complaint.service.RaiseComplaintCommand
import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UpdateProfileCommand
import org.devvikram.societymanagement.user.service.UserService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId

// Who may do what with complaints, end to end through the controller. Same setup as VisitorIntegrationTest:
// real local Postgres with seeded roles, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(ComplaintIntegrationTest.RecordingPushConfig::class)
class ComplaintIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var notificationListener: ComplaintNotificationListener
    @Autowired lateinit var pushNotifier: RecordingPushNotifier
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var complaintService: ComplaintService

    private var societyId = 0L
    private var flatId = 0L
    private var otherFlatId = 0L
    private lateinit var resident: User
    private lateinit var flatmate: User
    private lateinit var neighbour: User
    private lateinit var guard: User
    private lateinit var committee: User
    private lateinit var outsideCommittee: User

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
        societyId = societyService.createSociety("Complaint Test Society").id!!
        val wing = wingService.addWing(societyId, "A")
        flatId = flatService.addFlat(wing.id!!, "101").id!!
        otherFlatId = flatService.addFlat(wing.id!!, "102").id!!
        resident = user("RESIDENT", flatId = flatId)
        flatmate = user("RESIDENT", flatId = flatId)
        neighbour = user("RESIDENT", flatId = otherFlatId)
        guard = user("GUARD")
        committee = user("COMMITTEE")
        outsideCommittee = user("COMMITTEE", inSociety = societyService.createSociety("Other Society").id!!)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun complaintBy(raiser: User, onFlat: Long = flatId): Long =
        complaintService.raise(RaiseComplaintCommand(onFlat, ComplaintCategory.PLUMBING, "Kitchen tap leaking"), raiser.id!!)

    private fun raiseAs(user: User, body: String) = mockMvc.perform(
        post("/api/complaints")
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    )

    private fun patchAs(user: User, id: Long, body: String) = mockMvc.perform(
        patch("/api/complaints/$id")
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    )

    private fun actAs(user: User, id: Long, action: String) =
        mockMvc.perform(post("/api/complaints/$id/$action").header("Authorization", bearer(user)))

    private fun listAs(user: User, query: String = "") =
        mockMvc.perform(get("/api/complaints?societyId=$societyId$query").header("Authorization", bearer(user)))

    @Test
    fun `a resident raises a complaint for their own flat`() {
        raiseAs(resident, """{"flatId":$flatId,"category":"PLUMBING","description":"Kitchen tap leaking"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.societyId").value(societyId))
            .andExpect(jsonPath("$.flatNumber").value("101"))
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.raisedById").value(resident.id!!))
    }

    @Test
    fun `nobody raises for a flat that isn't theirs, and guards cannot raise at all`() {
        raiseAs(resident, """{"flatId":$otherFlatId,"category":"PLUMBING","description":"x"}""")
            .andExpect(status().isForbidden)
        raiseAs(guard, """{"flatId":$flatId,"category":"PLUMBING","description":"x"}""")
            .andExpect(status().isForbidden)
        raiseAs(outsideCommittee, """{"flatId":$flatId,"category":"PLUMBING","description":"x"}""")
            .andExpect(status().isForbidden)
    }

    @Test
    fun `missing fields or an unknown category are rejected`() {
        raiseAs(resident, """{"flatId":$flatId,"category":"PLUMBING","description":"  "}""").andExpect(status().isBadRequest)
        raiseAs(resident, """{"flatId":$flatId,"description":"x"}""").andExpect(status().isBadRequest)
        raiseAs(resident, """{"flatId":$flatId,"category":"GARDENING","description":"x"}""").andExpect(status().isBadRequest)
    }

    @Test
    fun `residents see their flat's complaints, the committee sees all, outsiders none`() {
        val own = complaintBy(resident)
        val neighbours = complaintBy(neighbour, otherFlatId)

        listAs(flatmate).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(own))
        listAs(resident, "&flatId=$otherFlatId").andExpect(status().isForbidden)
        listAs(committee).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(2))
        listAs(committee, "&flatId=$otherFlatId").andExpect(jsonPath("$[0].id").value(neighbours))
        listAs(outsideCommittee).andExpect(status().isForbidden)
        listAs(guard).andExpect(status().isForbidden)

        mockMvc.perform(get("/api/complaints/$neighbours").header("Authorization", bearer(resident)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/complaints/$own").header("Authorization", bearer(flatmate)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.description").value("Kitchen tap leaking"))
    }

    @Test
    fun `only the author edits or withdraws, not a flatmate or the committee`() {
        val id = complaintBy(resident)

        patchAs(flatmate, id, """{"description":"Changed"}""").andExpect(status().isForbidden)
        patchAs(committee, id, """{"description":"Changed"}""").andExpect(status().isForbidden)
        patchAs(resident, id, """{"category":"ELECTRICAL","description":"Geyser too"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.category").value("ELECTRICAL"))
            .andExpect(jsonPath("$.updatedById").value(resident.id!!))

        mockMvc.perform(delete("/api/complaints/$id").header("Authorization", bearer(flatmate)))
            .andExpect(status().isForbidden)
        mockMvc.perform(delete("/api/complaints/$id").header("Authorization", bearer(resident)))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/complaints/$id").header("Authorization", bearer(resident)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `the committee works a complaint through to closed`() {
        val id = complaintBy(resident)

        actAs(committee, id, "start").andExpect(status().isOk).andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        actAs(committee, id, "resolve").andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESOLVED"))
        actAs(committee, id, "reopen").andExpect(status().isOk).andExpect(jsonPath("$.status").value("OPEN"))
        actAs(committee, id, "resolve").andExpect(status().isOk)
        actAs(committee, id, "close").andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CLOSED"))
            .andExpect(jsonPath("$.updatedById").value(committee.id!!))
    }

    @Test
    fun `residents and outsiders cannot change the status`() {
        val id = complaintBy(resident)

        listOf(resident, guard, outsideCommittee).forEach {
            actAs(it, id, "resolve").andExpect(status().isForbidden)
        }
    }

    @Test
    fun `invalid moves conflict, and a resolved complaint is locked for its author`() {
        val id = complaintBy(resident)

        actAs(committee, id, "close").andExpect(status().isConflict)
        actAs(committee, id, "resolve").andExpect(status().isOk)
        patchAs(resident, id, """{"description":"Too late"}""").andExpect(status().isConflict)
        mockMvc.perform(delete("/api/complaints/$id").header("Authorization", bearer(resident)))
            .andExpect(status().isConflict)
    }

    @Test
    fun `status notes build a timeline the flat's residents can read`() {
        val id = complaintBy(resident)

        mockMvc.perform(
            post("/api/complaints/$id/resolve").header("Authorization", bearer(committee))
                .contentType(MediaType.APPLICATION_JSON).content("""{"note":"Plumber replaced the washer"}""")
        ).andExpect(status().isOk)
        actAs(committee, id, "close").andExpect(status().isOk)

        mockMvc.perform(get("/api/complaints/$id/activity").header("Authorization", bearer(flatmate)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].fromStatus").doesNotExist())
            .andExpect(jsonPath("$[0].toStatus").value("OPEN"))
            .andExpect(jsonPath("$[0].actorId").value(resident.id!!))
            .andExpect(jsonPath("$[1].toStatus").value("RESOLVED"))
            .andExpect(jsonPath("$[1].note").value("Plumber replaced the washer"))
            .andExpect(jsonPath("$[1].actorName").value("COMMITTEE user"))
            .andExpect(jsonPath("$[2].fromStatus").value("RESOLVED"))
            .andExpect(jsonPath("$[2].note").doesNotExist())
        mockMvc.perform(get("/api/complaints/$id/activity").header("Authorization", bearer(neighbour)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `withdrawing a complaint removes its timeline too`() {
        val id = complaintBy(resident)
        complaintService.changeStatus(id, ComplaintStatus.IN_PROGRESS, committee.id!!, "On it")

        mockMvc.perform(delete("/api/complaints/$id").header("Authorization", bearer(resident)))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/complaints/$id/activity").header("Authorization", bearer(committee)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `the committee exports complaints as CSV, residents cannot`() {
        complaintBy(resident)
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
        fun exportAs(user: User) = mockMvc.perform(
            get("/api/complaints/export").param("societyId", societyId.toString()).param("from", today).param("to", today)
                .header("Authorization", bearer(user))
        )

        exportAs(committee)
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/csv;charset=UTF-8"))
            .andExpect(content().string(startsWith("id,wing,flat,category,description,status,")))
            .andExpect(content().string(containsString(",A,101,PLUMBING,Kitchen tap leaking,OPEN,")))
        listOf(resident, guard, outsideCommittee).forEach { exportAs(it).andExpect(status().isForbidden) }
    }

    @Test
    fun `a new complaint alerts the society's committee, not residents, guards or the raiser`() {
        deviceOf(committee, "committee-token")
        deviceOf(guard, "guard-token")
        deviceOf(flatmate, "flatmate-token")
        deviceOf(outsideCommittee, "outside-token")
        val raisingCommittee = deviceOf(user("COMMITTEE"), "raiser-token")
        pushNotifier.sent.clear()

        notificationListener.onRaised(
            ComplaintRaised(1L, societyId, raisingCommittee.id!!, "101", ComplaintCategory.PLUMBING)
        )

        val push = pushNotifier.sent.single()
        assertEquals(listOf("committee-token"), push.tokens.toList())
        assertEquals("New complaint", push.title)
        assertEquals("plumbing complaint from flat 101", push.body)
    }

    private fun deviceOf(user: User, token: String): User =
        userService.updateProfile(user.id!!, UpdateProfileCommand(fcmToken = token))

    @TestConfiguration
    class RecordingPushConfig {
        @Bean
        @Primary
        fun recordingPushNotifier() = RecordingPushNotifier()
    }

    class RecordingPushNotifier : PushNotifier {
        data class Push(val tokens: Collection<String>, val title: String, val body: String)

        val sent = mutableListOf<Push>()

        override fun send(tokens: Collection<String>, title: String, body: String, data: Map<String, String>) {
            sent += Push(tokens, title, body)
        }
    }
}
