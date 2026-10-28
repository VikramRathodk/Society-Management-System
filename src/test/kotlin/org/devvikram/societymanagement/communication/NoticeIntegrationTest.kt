package org.devvikram.societymanagement.communication

import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.communication.service.NoticeService
import org.devvikram.societymanagement.communication.service.PostNoticeCommand
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

// Who may do what with notices, end to end through the controller. Same setup as VisitorIntegrationTest:
// real local Postgres with seeded roles, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NoticeIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var noticeService: NoticeService

    private var societyId = 0L
    private lateinit var resident: User
    private lateinit var guard: User
    private lateinit var committee: User
    private lateinit var otherCommittee: User
    private lateinit var secretary: User
    private lateinit var outsideSecretary: User

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
        societyId = societyService.createSociety("Notice Test Society").id!!
        val wing = wingService.addWing(societyId, "A")
        val flatId = flatService.addFlat(wing.id!!, "101").id!!
        resident = user("RESIDENT", flatId = flatId)
        guard = user("GUARD")
        committee = user("COMMITTEE")
        otherCommittee = user("COMMITTEE")
        secretary = user("SECRETARY")
        outsideSecretary = user("SECRETARY", inSociety = societyService.createSociety("Other Society").id!!)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun noticeBy(author: User, title: String = "Water cut on Sunday"): Long =
        noticeService.post(PostNoticeCommand(societyId, title), author.id!!)

    private fun postAs(user: User, body: String) = mockMvc.perform(
        post("/api/societies/$societyId/notices")
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    )

    private fun patchAs(user: User, id: Long, body: String) = mockMvc.perform(
        patch("/api/notices/$id")
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    )

    @Test
    fun `committee posts a notice`() {
        postAs(committee, """{"title":"Water cut on Sunday","description":"10am to 2pm"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.societyId").value(societyId))
            .andExpect(jsonPath("$.title").value("Water cut on Sunday"))
            .andExpect(jsonPath("$.description").value("10am to 2pm"))
            .andExpect(jsonPath("$.postedById").value(committee.id!!))
            .andExpect(jsonPath("$.updatedAt").doesNotExist())
    }

    @Test
    fun `residents and guards cannot post, nor can a secretary of another society`() {
        listOf(resident, guard, outsideSecretary).forEach {
            postAs(it, """{"title":"Hello"}""").andExpect(status().isForbidden)
        }
    }

    @Test
    fun `a blank or missing title is rejected`() {
        postAs(committee, """{"title":"  "}""").andExpect(status().isBadRequest)
        postAs(committee, """{"description":"no title"}""").andExpect(status().isBadRequest)
    }

    @Test
    fun `everyone in the society reads the board newest first, outsiders cannot`() {
        noticeBy(committee, "First")
        val latest = noticeBy(committee, "Second")

        listOf(resident, guard, committee).forEach {
            mockMvc.perform(get("/api/societies/$societyId/notices").header("Authorization", bearer(it)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(latest))
        }
        mockMvc.perform(get("/api/societies/$societyId/notices").header("Authorization", bearer(outsideSecretary)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/notices/$latest").header("Authorization", bearer(outsideSecretary)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/notices/$latest").header("Authorization", bearer(resident)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Second"))
    }

    @Test
    fun `an unknown notice is not found`() {
        mockMvc.perform(get("/api/notices/999999999").header("Authorization", bearer(resident)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `committee edits its own notice but not another member's`() {
        val own = noticeBy(committee)
        val others = noticeBy(otherCommittee)

        patchAs(committee, own, """{"title":"Water cut moved to Monday"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Water cut moved to Monday"))
            .andExpect(jsonPath("$.updatedById").value(committee.id!!))
        patchAs(committee, others, """{"title":"Hijacked"}""").andExpect(status().isForbidden)
    }

    @Test
    fun `a secretary moderates anyone's notice and the edit is attributed to them`() {
        val id = noticeBy(committee)

        patchAs(secretary, id, """{"description":"Updated by the office"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.postedById").value(committee.id!!))
            .andExpect(jsonPath("$.updatedById").value(secretary.id!!))
            .andExpect(jsonPath("$.description").value("Updated by the office"))
        patchAs(outsideSecretary, id, """{"title":"Nope"}""").andExpect(status().isForbidden)
    }

    @Test
    fun `residents cannot edit or delete, even a notice of their own society`() {
        val id = noticeBy(committee)

        patchAs(resident, id, """{"title":"Nope"}""").andExpect(status().isForbidden)
        mockMvc.perform(delete("/api/notices/$id").header("Authorization", bearer(resident)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `committee deletes only its own notices, a secretary deletes any`() {
        val own = noticeBy(committee)
        val others = noticeBy(otherCommittee)

        mockMvc.perform(delete("/api/notices/$others").header("Authorization", bearer(committee)))
            .andExpect(status().isForbidden)
        mockMvc.perform(delete("/api/notices/$own").header("Authorization", bearer(committee)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/api/notices/$others").header("Authorization", bearer(secretary)))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/notices/$others").header("Authorization", bearer(secretary)))
            .andExpect(status().isNotFound)
    }
}
