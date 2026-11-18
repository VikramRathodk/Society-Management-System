package org.devvikram.societymanagement.governance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.governance.repository.MeetingRepository
import org.devvikram.societymanagement.policy.service.CreatePolicyCommand
import org.devvikram.societymanagement.policy.service.PolicyActor
import org.devvikram.societymanagement.policy.service.PolicyCategoryService
import org.devvikram.societymanagement.policy.service.PolicyService
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.ResidentType
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

// Minutes end to end: drafting a held meeting's minutes, approval by someone other than the drafter,
// who may read them, corrections, the notice-board announcement; and policy references on agenda items.
// Real local Postgres, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MinutesIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var meetingRepository: MeetingRepository
    @Autowired lateinit var policyService: PolicyService
    @Autowired lateinit var categoryService: PolicyCategoryService

    private var societyId = 0L
    private lateinit var resident: User
    private lateinit var committee: User
    private lateinit var chairman: User
    private lateinit var secretary: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(role: String, flatId: Long? = null, society: Long = societyId) = userService.createUser(
        CreateUserCommand(
            name = "$role user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = role, societyId = society, flatId = flatId, residentType = flatId?.let { ResidentType.OWNER }),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Minutes Test Society").id!!
        val wing = wingService.addWing(societyId, "B").id!!
        resident = user("RESIDENT", flatService.addFlat(wing, "201").id!!)
        committee = user("COMMITTEE")
        chairman = user("CHAIRMAN")
        secretary = user("SECRETARY")
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun call(user: User, method: HttpMethod, path: String, body: String? = null): ResultActions {
        val req = request(method, path).header("Authorization", bearer(user))
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(req)
    }

    private fun ResultActions.json(): String = andReturn().response.contentAsString

    private fun meeting(kind: String = "AGM", agenda: String = """[{"title":"Accounts","resolutionText":"Adopt the accounts"}]"""): Long {
        val at = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS)
        val json = call(
            secretary, HttpMethod.POST, "/api/societies/$societyId/meetings",
            """{"kind":"$kind","title":"$kind 2026","scheduledAt":"$at","place":"Hall","agenda":$agenda}""",
        ).andExpect(status().isCreated).json()
        return JsonPath.read<Number>(json, "$.id").toLong()
    }

    private fun held(kind: String = "AGM"): Long {
        val id = meeting(kind)
        call(secretary, HttpMethod.POST, "/api/meetings/$id/send-notice").andExpect(status().isOk)
        val m = meetingRepository.findById(id)!!
        m.scheduledAt = Instant.now().minus(Duration.ofHours(2))
        meetingRepository.save(m)
        call(secretary, HttpMethod.POST, "/api/meetings/$id/hold").andExpect(status().isOk)
        return id
    }

    private fun published(id: Long) {
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/minutes", """{"body":"# Minutes\nAccounts adopted."}""").andExpect(status().isOk)
        call(secretary, HttpMethod.POST, "/api/meetings/$id/minutes/submit").andExpect(status().isOk)
        call(chairman, HttpMethod.POST, "/api/meetings/$id/minutes/approve").andExpect(status().isOk)
    }

    @Test
    fun `minutes are written only once the meeting is held`() {
        val id = meeting()
        call(secretary, HttpMethod.POST, "/api/meetings/$id/send-notice")
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/minutes", """{"body":"Too early"}""").andExpect(status().isConflict)
        call(secretary, HttpMethod.GET, "/api/meetings/$id/minutes").andExpect(status().isNotFound)
    }

    @Test
    fun `the secretary drafts, only drafters and approvers see the draft`() {
        val id = held()
        call(committee, HttpMethod.PUT, "/api/meetings/$id/minutes", """{"body":"x"}""").andExpect(status().isForbidden)
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/minutes", """{"body":"First draft"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.draftedBy.id").value(secretary.id!!))
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/minutes", """{"body":"Second draft"}""").andExpect(jsonPath("$.body").value("Second draft"))
        call(resident, HttpMethod.GET, "/api/meetings/$id/minutes").andExpect(status().isNotFound)
        call(committee, HttpMethod.GET, "/api/meetings/$id/minutes").andExpect(status().isNotFound)
        call(chairman, HttpMethod.GET, "/api/meetings/$id/minutes").andExpect(status().isOk)
    }

    @Test
    fun `approval is by someone other than the drafter, and publishes the minutes`() {
        val id = held()
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/minutes", """{"body":"Accounts adopted."}""")
        call(chairman, HttpMethod.POST, "/api/meetings/$id/minutes/approve").andExpect(status().isConflict) // not submitted
        call(secretary, HttpMethod.POST, "/api/meetings/$id/minutes/submit").andExpect(status().isOk).andExpect(jsonPath("$.status").value("PENDING"))
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/minutes", """{"body":"Change"}""").andExpect(status().isConflict)
        call(secretary, HttpMethod.POST, "/api/meetings/$id/minutes/approve").andExpect(status().isForbidden) // the drafter
        call(committee, HttpMethod.POST, "/api/meetings/$id/minutes/approve").andExpect(status().isForbidden)

        call(chairman, HttpMethod.POST, "/api/meetings/$id/minutes/send-back", """{"reason":"Add the attendance"}""")
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.reviewReason").value("Add the attendance"))
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/minutes", """{"body":"Present: 12 flats. Accounts adopted."}""")
        call(secretary, HttpMethod.POST, "/api/meetings/$id/minutes/submit")
        call(chairman, HttpMethod.POST, "/api/meetings/$id/minutes/approve")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.approvedBy.id").value(chairman.id!!))
            .andExpect(jsonPath("$.reviewReason").doesNotExist())

        call(resident, HttpMethod.GET, "/api/meetings/$id/minutes").andExpect(status().isOk).andExpect(jsonPath("$.body").value("Present: 12 flats. Accounts adopted."))
        call(resident, HttpMethod.GET, "/api/meetings/$id").andExpect(jsonPath("$.status").value("MINUTES_PUBLISHED"))
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/attendance", """{"userIds":[]}""").andExpect(status().isConflict)
    }

    @Test
    fun `published minutes never change, corrections are added`() {
        val id = held()
        published(id)
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/minutes", """{"body":"Rewrite"}""").andExpect(status().isConflict)
        call(resident, HttpMethod.POST, "/api/meetings/$id/minutes/addenda", """{"text":"x"}""").andExpect(status().isForbidden)
        call(secretary, HttpMethod.POST, "/api/meetings/$id/minutes/addenda", """{"text":"Item 1: the auditor was Rao & Co."}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.addenda.length()").value(1))
            .andExpect(jsonPath("$.addenda[0].createdBy.id").value(secretary.id!!))
        call(resident, HttpMethod.GET, "/api/meetings/$id/minutes").andExpect(jsonPath("$.addenda[0].text").value("Item 1: the auditor was Rao & Co."))
    }

    @Test
    fun `committee meeting minutes stay with the committee and aren't announced`() {
        val id = held("COMMITTEE")
        published(id)
        call(committee, HttpMethod.GET, "/api/meetings/$id/minutes").andExpect(status().isOk)
        call(resident, HttpMethod.GET, "/api/meetings/$id/minutes").andExpect(status().isNotFound)
        call(secretary, HttpMethod.POST, "/api/meetings/$id/minutes/announce").andExpect(status().isConflict)
    }

    @Test
    fun `general meeting minutes are announced on the notice board once`() {
        val id = held()
        published(id)
        call(secretary, HttpMethod.POST, "/api/meetings/$id/minutes/announce")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.announcedNoticeId").isNumber)
        call(resident, HttpMethod.GET, "/api/societies/$societyId/notices")
            .andExpect(jsonPath("$[0].title").value("Minutes published: AGM 2026"))
        call(secretary, HttpMethod.POST, "/api/meetings/$id/minutes/announce").andExpect(status().isConflict)
    }

    @Test
    fun `the signed minutes can be attached as a PDF`() {
        val id = held()
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/minutes", """{"body":""}""")
        val pdf = "%PDF-1.4 signed".toByteArray()
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/meetings/$id/minutes/attachment")
                .file(MockMultipartFile("file", "minutes.pdf", "application/pdf", pdf))
                .header("Authorization", bearer(secretary))
        ).andExpect(status().isOk).andExpect(jsonPath("$.attachment.name").value("minutes.pdf"))
        call(secretary, HttpMethod.POST, "/api/meetings/$id/minutes/submit").andExpect(status().isOk) // a file is enough
        call(chairman, HttpMethod.POST, "/api/meetings/$id/minutes/approve")
        call(resident, HttpMethod.GET, "/api/meetings/$id/minutes/attachment")
            .andExpect(status().isOk)
            .andExpect(content().bytes(pdf))
    }

    @Test
    fun `an agenda item can refer to one of the society's policies`() {
        val category = categoryService.list(societyId).first().id!!
        val policyId = policyService.create(
            societyId, CreatePolicyCommand(category, "Parking rules"), PolicyActor(secretary.id!!, canDraft = true, canApprove = true),
        )
        val id = meeting(agenda = """[{"title":"Parking","resolutionText":"Adopt the parking rules","policyId":$policyId}]""")
        call(secretary, HttpMethod.GET, "/api/meetings/$id")
            .andExpect(jsonPath("$.agenda[0].policy.id").value(policyId))
            .andExpect(jsonPath("$.agenda[0].policy.title").value("Parking rules"))

        val other = societyService.createSociety("Elsewhere").id!!
        val otherCategory = categoryService.list(other).first().id!!
        val foreign = policyService.create(other, CreatePolicyCommand(otherCategory, "Theirs"), PolicyActor(secretary.id!!, canDraft = true, canApprove = true))
        val at = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS)
        call(
            secretary, HttpMethod.POST, "/api/societies/$societyId/meetings",
            """{"kind":"AGM","title":"x","scheduledAt":"$at","place":"Hall","agenda":[{"title":"y","policyId":$foreign}]}""",
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `voters see their flats by name`() {
        val id = meeting()
        call(secretary, HttpMethod.POST, "/api/meetings/$id/send-notice")
        val item = JsonPath.read<Number>(call(secretary, HttpMethod.GET, "/api/meetings/$id").json(), "$.agenda[0].id").toLong()
        call(secretary, HttpMethod.POST, "/api/agenda-items/$item/open-voting").andExpect(status().isOk)
        call(resident, HttpMethod.GET, "/api/meetings/$id")
            .andExpect(jsonPath("$.agenda[0].yourFlats[0].label").value("B-201"))
            .andExpect(jsonPath("$.agenda[0].yourFlats[0].voted").value(false))
        call(resident, HttpMethod.POST, "/api/agenda-items/$item/vote", """{"choice":"FOR"}""")
            .andExpect(jsonPath("$.agenda[0].yourFlats[0].voted").value(true))
    }
}
