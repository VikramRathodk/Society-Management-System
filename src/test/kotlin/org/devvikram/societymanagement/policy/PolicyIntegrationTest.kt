package org.devvikram.societymanagement.policy

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
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
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId

// Policies end to end through the controllers: categories, drafting, approval, publishing, versions.
// Real local Postgres with seeded roles, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PolicyIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var settingsService: SocietySettingsService

    private var societyId = 0L
    private lateinit var resident: User
    private lateinit var guard: User
    private lateinit var committee: User
    private lateinit var chairman: User
    private lateinit var secretary: User
    private lateinit var outsideSecretary: User

    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))

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
        societyId = societyService.createSociety("Policy Test Society").id!!
        val wing = wingService.addWing(societyId, "A")
        resident = user("RESIDENT", flatId = flatService.addFlat(wing.id!!, "101").id!!)
        guard = user("GUARD")
        committee = user("COMMITTEE")
        chairman = user("CHAIRMAN")
        secretary = user("SECRETARY")
        outsideSecretary = user("SECRETARY", inSociety = societyService.createSociety("Other Society").id!!)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun call(user: User, method: HttpMethod, path: String, body: String? = null): ResultActions {
        val request = when (method) {
            HttpMethod.GET -> get(path)
            HttpMethod.POST -> post(path)
            HttpMethod.PUT -> put(path)
            HttpMethod.PATCH -> patch(path)
            HttpMethod.DELETE -> delete(path)
            else -> error("Unsupported $method")
        }.header("Authorization", bearer(user))
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(request)
    }

    private fun ResultActions.json(): String = andReturn().response.contentAsString

    private fun setting(key: String, value: Any) = settingsService.update(societyId, mapOf(key to value), secretary.id!!)

    private fun categoryId(name: String = "Parking"): Long {
        val body = call(resident, HttpMethod.GET, "/api/societies/$societyId/policy-categories").andExpect(status().isOk).json()
        return JsonPath.read<List<Number>>(body, "$[?(@.name == '$name')].id").single().toLong()
    }

    // Returns (policyId, versionId of the first draft).
    private fun draft(
        author: User = committee,
        title: String = "Parking rules",
        body: String = "Park only in your own slot.",
        extra: String = "",
    ): Pair<Long, Long> {
        val json = call(
            author, HttpMethod.POST, "/api/societies/$societyId/policies",
            """{"categoryId":${categoryId()},"title":"$title","body":"$body"$extra}""",
        ).andExpect(status().isCreated).json()
        return JsonPath.read<Number>(json, "$.summary.id").toLong() to JsonPath.read<Number>(json, "$.open.id").toLong()
    }

    private fun published(author: User = committee, title: String = "Parking rules"): Pair<Long, Long> {
        val (policyId, versionId) = draft(author, title)
        call(author, HttpMethod.POST, "/api/policy-versions/$versionId/submit").andExpect(status().isOk)
        call(if (author.id == chairman.id) secretary else chairman, HttpMethod.POST, "/api/policy-versions/$versionId/approve")
            .andExpect(status().isOk)
        return policyId to versionId
    }

    private fun newVersion(policyId: Long, by: User = committee): Long {
        val json = call(by, HttpMethod.POST, "/api/policies/$policyId/versions").andExpect(status().isCreated).json()
        return JsonPath.read<Number>(json, "$.open.id").toLong()
    }

    // ---- Categories ----

    @Test
    fun `every society starts with the default categories, managed by the secretary`() {
        call(guard, HttpMethod.GET, "/api/societies/$societyId/policy-categories")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(11))
            .andExpect(jsonPath("$[0].name").value("By-laws"))
            .andExpect(jsonPath("$[10].name").value("Other"))

        call(secretary, HttpMethod.POST, "/api/societies/$societyId/policy-categories", """{"name":"Swimming pool"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.position").value(12))
        call(secretary, HttpMethod.POST, "/api/societies/$societyId/policy-categories", """{"name":"  swimming   POOL "}""")
            .andExpect(status().isConflict)
        call(committee, HttpMethod.POST, "/api/societies/$societyId/policy-categories", """{"name":"Gym"}""")
            .andExpect(status().isForbidden)

        val pets = categoryId("Pets")
        call(secretary, HttpMethod.PATCH, "/api/policy-categories/$pets", """{"name":"Pets & animals","hidden":true}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Pets & animals"))
            .andExpect(jsonPath("$.hidden").value(true))
        call(
            committee, HttpMethod.POST, "/api/societies/$societyId/policies",
            """{"categoryId":$pets,"title":"Dogs on leash"}""",
        ).andExpect(status().isBadRequest)
        call(outsideSecretary, HttpMethod.GET, "/api/societies/$societyId/policy-categories").andExpect(status().isForbidden)
    }

    // ---- Drafting and visibility ----

    @Test
    fun `committee drafts, residents and guards never see drafts`() {
        val (policyId, _) = draft()
        call(committee, HttpMethod.GET, "/api/policies/$policyId")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.status").value("DRAFT"))
            .andExpect(jsonPath("$.open.number").value(1))
            .andExpect(jsonPath("$.open.status").value("DRAFT"))
            .andExpect(jsonPath("$.current").doesNotExist())

        listOf(resident, guard).forEach {
            call(it, HttpMethod.GET, "/api/policies/$policyId").andExpect(status().isNotFound)
            call(it, HttpMethod.GET, "/api/societies/$societyId/policies?filter=drafts").andExpect(status().isForbidden)
            call(it, HttpMethod.GET, "/api/societies/$societyId/policies")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(0))
        }
        call(chairman, HttpMethod.GET, "/api/societies/$societyId/policies?filter=drafts")
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].openVersion.createdBy.name").value("COMMITTEE user"))
    }

    @Test
    fun `residents, guards and other societies cannot draft`() {
        listOf(resident, guard, outsideSecretary).forEach {
            call(it, HttpMethod.POST, "/api/societies/$societyId/policies", """{"categoryId":${categoryId()},"title":"X"}""")
                .andExpect(status().isForbidden)
        }
        call(committee, HttpMethod.POST, "/api/societies/$societyId/policies", """{"categoryId":${categoryId()},"title":" "}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `only the author or an approver edits a draft`() {
        val (_, versionId) = draft()
        val other = user("COMMITTEE")
        call(other, HttpMethod.PUT, "/api/policy-versions/$versionId", """{"body":"Mine now"}""").andExpect(status().isForbidden)
        call(chairman, HttpMethod.PUT, "/api/policy-versions/$versionId", """{"body":"Tidied","changeSummary":"Wording"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.open.body").value("Tidied"))
            .andExpect(jsonPath("$.open.changeSummary").value("Wording"))
    }

    @Test
    fun `a never-published policy is deleted with its draft`() {
        val (policyId, versionId) = draft()
        call(user("COMMITTEE"), HttpMethod.DELETE, "/api/policy-versions/$versionId").andExpect(status().isForbidden)
        call(committee, HttpMethod.DELETE, "/api/policy-versions/$versionId").andExpect(status().isNoContent)
        call(committee, HttpMethod.GET, "/api/policies/$policyId").andExpect(status().isNotFound)
    }

    // ---- Approval ----

    @Test
    fun `submit, approve and publish to everyone in the society`() {
        val (policyId, versionId) = draft()
        call(committee, HttpMethod.POST, "/api/policy-versions/$versionId/submit")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.open.status").value("PENDING"))
        call(committee, HttpMethod.PUT, "/api/policy-versions/$versionId", """{"body":"Changed"}""").andExpect(status().isConflict)
        call(chairman, HttpMethod.GET, "/api/societies/$societyId/policies?filter=awaiting-approval")
            .andExpect(jsonPath("$.length()").value(1))

        call(committee, HttpMethod.POST, "/api/policy-versions/$versionId/approve").andExpect(status().isForbidden)
        call(chairman, HttpMethod.POST, "/api/policy-versions/$versionId/approve")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.status").value("ACTIVE"))
            .andExpect(jsonPath("$.current.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.current.effectiveFrom").value(today.toString()))
            .andExpect(jsonPath("$.current.inForce").value(true))
            .andExpect(jsonPath("$.current.reviewedBy.id").value(chairman.id!!))
            .andExpect(jsonPath("$.open").doesNotExist())

        listOf(resident, guard).forEach {
            call(it, HttpMethod.GET, "/api/societies/$societyId/policies")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Parking rules"))
                .andExpect(jsonPath("$[0].category.name").value("Parking"))
                .andExpect(jsonPath("$[0].inForceVersion").value(1))
                .andExpect(jsonPath("$[0].openVersion").doesNotExist())
            call(it, HttpMethod.GET, "/api/policies/$policyId/versions/1")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.body").value("Park only in your own slot."))
        }
    }

    @Test
    fun `sent back with a reason, edited and resubmitted, or withdrawn by its author`() {
        val (_, versionId) = draft()
        call(committee, HttpMethod.POST, "/api/policy-versions/$versionId/submit").andExpect(status().isOk)
        call(chairman, HttpMethod.POST, "/api/policy-versions/$versionId/send-back", """{"reason":" "}""")
            .andExpect(status().isBadRequest)
        call(chairman, HttpMethod.POST, "/api/policy-versions/$versionId/send-back", """{"reason":"Add visitor parking"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.open.status").value("DRAFT"))
            .andExpect(jsonPath("$.open.reviewReason").value("Add visitor parking"))

        call(committee, HttpMethod.PUT, "/api/policy-versions/$versionId", """{"body":"Own slot; visitors at the gate."}""")
            .andExpect(status().isOk)
        call(committee, HttpMethod.POST, "/api/policy-versions/$versionId/submit").andExpect(status().isOk)
        call(chairman, HttpMethod.POST, "/api/policy-versions/$versionId/withdraw").andExpect(status().isForbidden)
        call(committee, HttpMethod.POST, "/api/policy-versions/$versionId/withdraw")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.open.status").value("DRAFT"))
    }

    @Test
    fun `an approver cannot approve their own draft unless the society allows it`() {
        val (_, versionId) = draft(author = chairman)
        call(chairman, HttpMethod.POST, "/api/policy-versions/$versionId/submit").andExpect(status().isOk)
        call(chairman, HttpMethod.POST, "/api/policy-versions/$versionId/approve").andExpect(status().isForbidden)

        setting(SocietySettingKeys.POLICY_SELF_APPROVAL_ALLOWED, true)
        call(chairman, HttpMethod.POST, "/api/policy-versions/$versionId/approve")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.number").value(1))
    }

    @Test
    fun `with approval off the author publishes directly, and submitting is refused`() {
        val (_, versionId) = draft()
        call(committee, HttpMethod.POST, "/api/policy-versions/$versionId/publish").andExpect(status().isConflict)

        setting(SocietySettingKeys.POLICY_APPROVAL_REQUIRED, false)
        call(committee, HttpMethod.POST, "/api/policy-versions/$versionId/submit").andExpect(status().isConflict)
        call(user("COMMITTEE"), HttpMethod.POST, "/api/policy-versions/$versionId/publish").andExpect(status().isForbidden)
        call(committee, HttpMethod.POST, "/api/policy-versions/$versionId/publish")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.publishedBy.id").value(committee.id!!))
    }

    @Test
    fun `a draft without text cannot be submitted`() {
        val (_, versionId) = draft(body = "")
        call(committee, HttpMethod.POST, "/api/policy-versions/$versionId/submit").andExpect(status().isBadRequest)
    }

    // ---- Versions ----

    @Test
    fun `one change in progress per policy, and published versions never change`() {
        val (policyId, firstVersionId) = published()
        call(committee, HttpMethod.PUT, "/api/policy-versions/$firstVersionId", """{"body":"Rewrite"}""").andExpect(status().isConflict)
        call(committee, HttpMethod.DELETE, "/api/policies/$policyId").andExpect(status().isConflict)

        val secondId = newVersion(policyId)
        call(committee, HttpMethod.POST, "/api/policies/$policyId/versions").andExpect(status().isConflict)
        call(committee, HttpMethod.GET, "/api/policies/$policyId")
            .andExpect(jsonPath("$.open.id").value(secondId))
            .andExpect(jsonPath("$.open.number").value(2))
            .andExpect(jsonPath("$.open.body").value("Park only in your own slot."))
            .andExpect(jsonPath("$.current.number").value(1))

        call(committee, HttpMethod.PUT, "/api/policy-versions/$secondId", """{"body":"Two-wheelers in the basement.","changeSummary":"Two-wheelers"}""")
            .andExpect(status().isOk)
        call(committee, HttpMethod.POST, "/api/policy-versions/$secondId/submit").andExpect(status().isOk)
        call(chairman, HttpMethod.POST, "/api/policy-versions/$secondId/approve").andExpect(status().isOk)

        call(resident, HttpMethod.GET, "/api/policies/$policyId")
            .andExpect(jsonPath("$.current.number").value(2))
            .andExpect(jsonPath("$.current.body").value("Two-wheelers in the basement."))
            .andExpect(jsonPath("$.versions.length()").value(2))
            .andExpect(jsonPath("$.versions[0].number").value(2))
            .andExpect(jsonPath("$.versions[0].changeSummary").value("Two-wheelers"))
            .andExpect(jsonPath("$.versions[1].status").value("SUPERSEDED"))
        call(resident, HttpMethod.GET, "/api/policies/$policyId/versions/1")
            .andExpect(jsonPath("$.body").value("Park only in your own slot."))
            .andExpect(jsonPath("$.inForce").value(false))
    }

    @Test
    fun `a change taking effect later leaves the current text in force until then`() {
        val (policyId, _) = published()
        val secondId = newVersion(policyId)
        val later = today.plusDays(10)
        call(committee, HttpMethod.PUT, "/api/policy-versions/$secondId", """{"body":"New timings","effectiveFrom":"$later"}""")
            .andExpect(status().isOk)
        call(committee, HttpMethod.POST, "/api/policy-versions/$secondId/submit").andExpect(status().isOk)
        call(chairman, HttpMethod.POST, "/api/policy-versions/$secondId/approve").andExpect(status().isOk)

        call(resident, HttpMethod.GET, "/api/societies/$societyId/policies")
            .andExpect(jsonPath("$[0].currentVersion").value(2))
            .andExpect(jsonPath("$[0].inForceVersion").value(1))
            .andExpect(jsonPath("$[0].takesEffectOn").value(later.toString()))
        call(resident, HttpMethod.GET, "/api/policies/$policyId/versions/1").andExpect(jsonPath("$.inForce").value(true))
        call(resident, HttpMethod.GET, "/api/policies/$policyId")
            .andExpect(jsonPath("$.current.number").value(2))
            .andExpect(jsonPath("$.current.inForce").value(false))
    }

    @Test
    fun `a change cannot take effect before the text it replaces`() {
        val (policyId, _) = published()
        val secondId = newVersion(policyId)
        call(committee, HttpMethod.PUT, "/api/policy-versions/$secondId", """{"body":"Backdated","effectiveFrom":"${today.minusDays(1)}"}""")
            .andExpect(status().isOk)
        call(committee, HttpMethod.POST, "/api/policy-versions/$secondId/submit").andExpect(status().isBadRequest)
    }

    @Test
    fun `a dropped change leaves the published policy as it was`() {
        val (policyId, _) = published()
        val secondId = newVersion(policyId)
        call(committee, HttpMethod.DELETE, "/api/policy-versions/$secondId").andExpect(status().isNoContent)
        call(committee, HttpMethod.GET, "/api/policies/$policyId")
            .andExpect(jsonPath("$.open").doesNotExist())
            .andExpect(jsonPath("$.versions.length()").value(1))
    }

    // ---- Policy details, archive ----

    @Test
    fun `after publication only approvers rename or move a policy`() {
        val (policyId, _) = published()
        call(committee, HttpMethod.PATCH, "/api/policies/$policyId", """{"title":"Car parking"}""").andExpect(status().isForbidden)
        call(chairman, HttpMethod.PATCH, "/api/policies/$policyId", """{"title":"Car parking","categoryId":${categoryId("Security & visitors")}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.title").value("Car parking"))
            .andExpect(jsonPath("$.summary.category.name").value("Security & visitors"))
    }

    @Test
    fun `archived policies leave the rules page and take no changes until restored`() {
        val (policyId, _) = published()
        call(committee, HttpMethod.POST, "/api/policies/$policyId/archive").andExpect(status().isForbidden)
        call(chairman, HttpMethod.POST, "/api/policies/$policyId/archive")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.status").value("ARCHIVED"))
        call(resident, HttpMethod.GET, "/api/societies/$societyId/policies").andExpect(jsonPath("$.length()").value(0))
        call(resident, HttpMethod.GET, "/api/societies/$societyId/policies?filter=archived").andExpect(jsonPath("$.length()").value(1))
        call(committee, HttpMethod.POST, "/api/policies/$policyId/versions").andExpect(status().isConflict)

        call(chairman, HttpMethod.POST, "/api/policies/$policyId/restore").andExpect(jsonPath("$.summary.status").value("ACTIVE"))
        call(resident, HttpMethod.GET, "/api/societies/$societyId/policies").andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `the rules page filters by category and title words`() {
        published(title = "Parking rules")
        draft(title = "Pool timings")
        call(resident, HttpMethod.GET, "/api/societies/$societyId/policies?q=PARK")
            .andExpect(jsonPath("$.length()").value(1))
        call(resident, HttpMethod.GET, "/api/societies/$societyId/policies?q=pool")
            .andExpect(jsonPath("$.length()").value(0))
        call(resident, HttpMethod.GET, "/api/societies/$societyId/policies?categoryId=${categoryId("Pets")}")
            .andExpect(jsonPath("$.length()").value(0))
        call(resident, HttpMethod.GET, "/api/societies/$societyId/policies?filter=everything").andExpect(status().isBadRequest)
    }

    // ---- Attachment ----

    @Test
    fun `a draft takes a PDF that residents can open once published`() {
        val (policyId, versionId) = draft()
        val pdf = "%PDF-1.4 by-laws".toByteArray()
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/policy-versions/$versionId/attachment")
                .file(MockMultipartFile("file", "bylaws.pdf", "application/pdf", pdf))
                .header("Authorization", bearer(committee))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.open.attachment.name").value("bylaws.pdf"))
            .andExpect(jsonPath("$.open.attachment.contentType").value("application/pdf"))
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/policy-versions/$versionId/attachment")
                .file(MockMultipartFile("file", "notes.txt", "text/plain", "hello".toByteArray()))
                .header("Authorization", bearer(committee))
        ).andExpect(status().isBadRequest)

        call(resident, HttpMethod.GET, "/api/policy-versions/$versionId/attachment").andExpect(status().isNotFound)
        call(committee, HttpMethod.POST, "/api/policy-versions/$versionId/submit").andExpect(status().isOk)
        call(chairman, HttpMethod.POST, "/api/policy-versions/$versionId/approve").andExpect(status().isOk)
        call(resident, HttpMethod.GET, "/api/policy-versions/$versionId/attachment")
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/pdf"))
            .andExpect(content().bytes(pdf))

        // A new version starts with its own copy of the file.
        val secondId = newVersion(policyId)
        call(committee, HttpMethod.DELETE, "/api/policy-versions/$secondId/attachment")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.open.attachment").doesNotExist())
        call(resident, HttpMethod.GET, "/api/policy-versions/$versionId/attachment").andExpect(status().isOk)
    }
}
