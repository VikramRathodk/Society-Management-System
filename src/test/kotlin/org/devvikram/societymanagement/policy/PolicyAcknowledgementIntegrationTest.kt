package org.devvikram.societymanagement.policy

import com.jayway.jsonpath.JsonPath
import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.auth.security.JwtService
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertContains
import kotlin.test.assertEquals

// Residents acknowledging policies, and the committee's per-flat follow-up. Real local Postgres,
// rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PolicyAcknowledgementIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var entityManager: EntityManager

    private var societyId = 0L
    // 101: owner + family; 102: owner + tenant (rented); 103: owner only.
    private lateinit var owner101: User
    private lateinit var family101: User
    private lateinit var owner102: User
    private lateinit var tenant102: User
    private lateinit var owner103: User
    private lateinit var guard: User
    private lateinit var committee: User
    private lateinit var chairman: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(name: String, roleCode: String, flatId: Long? = null, type: ResidentType? = null) = userService.createUser(
        CreateUserCommand(
            name = name,
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = societyId, flatId = flatId, residentType = type),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Acknowledgement Test Society").id!!
        val wing = wingService.addWing(societyId, "A")
        val f101 = flatService.addFlat(wing.id!!, "101").id!!
        val f102 = flatService.addFlat(wing.id!!, "102").id!!
        val f103 = flatService.addFlat(wing.id!!, "103").id!!
        flatService.addFlat(wing.id!!, "104") // nobody lives here: not in the report
        owner101 = user("Asha", "RESIDENT", f101, ResidentType.OWNER)
        family101 = user("Bela", "RESIDENT", f101, ResidentType.FAMILY)
        owner102 = user("Chetan", "RESIDENT", f102, ResidentType.OWNER)
        tenant102 = user("Dev", "RESIDENT", f102, ResidentType.TENANT)
        owner103 = user("Esha", "RESIDENT", f103)
        guard = user("Guard", "GUARD")
        committee = user("Committee", "COMMITTEE")
        chairman = user("Chairman", "CHAIRMAN")
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun postAs(user: User, path: String, body: String? = null): ResultActions {
        val request = post(path).header("Authorization", bearer(user))
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(request)
    }

    private fun getAs(user: User, path: String) = mockMvc.perform(get(path).header("Authorization", bearer(user)))

    private fun ResultActions.json(): String = andReturn().response.contentAsString

    private fun categoryId(): Long {
        val body = getAs(committee, "/api/societies/$societyId/policy-categories").json()
        return JsonPath.read<List<Number>>(body, "$[?(@.name == 'Pets')].id").single().toLong()
    }

    private fun approve(versionId: Long) {
        postAs(committee, "/api/policy-versions/$versionId/submit").andExpect(status().isOk)
        postAs(chairman, "/api/policy-versions/$versionId/approve").andExpect(status().isOk)
    }

    // Returns (policyId, versionId).
    private fun publishedPolicy(requiresAcknowledgement: Boolean = true): Pair<Long, Long> {
        val json = postAs(
            committee, "/api/societies/$societyId/policies",
            """{"categoryId":${categoryId()},"title":"Pets","body":"Dogs on a leash.","requiresAcknowledgement":$requiresAcknowledgement}""",
        ).andExpect(status().isCreated).json()
        val policyId = JsonPath.read<Number>(json, "$.summary.id").toLong()
        val versionId = JsonPath.read<Number>(json, "$.open.id").toLong()
        approve(versionId)
        return policyId to versionId
    }

    private fun publishChange(policyId: Long, material: Boolean): Long {
        val json = postAs(committee, "/api/policies/$policyId/versions").andExpect(status().isCreated).json()
        val versionId = JsonPath.read<Number>(json, "$.open.id").toLong()
        mockMvc.perform(
            put("/api/policy-versions/$versionId")
                .header("Authorization", bearer(committee))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"body":"Dogs on a short leash.","materialChange":$material}""")
        ).andExpect(status().isOk)
        approve(versionId)
        return versionId
    }

    private fun acknowledge(user: User, versionId: Long) = postAs(user, "/api/policy-versions/$versionId/acknowledge")

    private fun report(state: String = "all") = getAs(committee, "/api/policies/${currentPolicy}/acknowledgements?state=$state")

    private var currentPolicy = 0L

    @Test
    fun `a resident acknowledges once, and sees it on the rules page`() {
        val (policyId, versionId) = publishedPolicy()
        getAs(owner101, "/api/societies/$societyId/policies")
            .andExpect(jsonPath("$[0].yourAcknowledgement.version").value(1))
            .andExpect(jsonPath("$[0].yourAcknowledgement.acknowledgedAt").doesNotExist())
        getAs(owner101, "/api/societies/$societyId/policies/pending-acknowledgements")
            .andExpect(jsonPath("$.length()").value(1))

        val first = acknowledge(owner101, versionId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.policyId").value(policyId))
            .andExpect(jsonPath("$.version").value(1))
            .json()
        val again = acknowledge(owner101, versionId).andExpect(status().isOk).json()
        assertEquals(JsonPath.read<String>(first, "$.acknowledgedAt"), JsonPath.read<String>(again, "$.acknowledgedAt"))

        getAs(owner101, "/api/policies/$policyId")
            .andExpect(jsonPath("$.summary.yourAcknowledgement.acknowledgedAt").exists())
        getAs(owner101, "/api/societies/$societyId/policies/pending-acknowledgements")
            .andExpect(jsonPath("$.length()").value(0))
        // Not a resident: no acknowledgement state at all.
        getAs(committee, "/api/societies/$societyId/policies").andExpect(jsonPath("$[0].yourAcknowledgement").doesNotExist())
    }

    @Test
    fun `only flat roles acknowledge, and only policies that ask for it`() {
        val (_, versionId) = publishedPolicy()
        acknowledge(guard, versionId).andExpect(status().isForbidden)
        acknowledge(committee, versionId).andExpect(status().isForbidden)

        val (_, plainVersion) = publishedPolicy(requiresAcknowledgement = false)
        acknowledge(owner101, plainVersion).andExpect(status().isConflict)
        getAs(owner101, "/api/societies/$societyId/policies/pending-acknowledgements").andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `flats are done when the owner, or the tenant of a rented flat, has acknowledged`() {
        val (policyId, versionId) = publishedPolicy()
        currentPolicy = policyId
        acknowledge(family101, versionId) // family alone doesn't complete 101
        acknowledge(owner102, versionId) // 102 is rented: the owner doesn't complete it
        acknowledge(owner103, versionId) // no type counts as the owner

        report()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalFlats").value(3))
            .andExpect(jsonPath("$.doneFlats").value(1))
            .andExpect(jsonPath("$.pendingFlats").value(2))
            .andExpect(jsonPath("$.people").value(3))
            .andExpect(jsonPath("$.flats[0].flatNumber").value("101"))
            .andExpect(jsonPath("$.flats[0].done").value(false))
            .andExpect(jsonPath("$.flats[0].members.length()").value(2))
            .andExpect(jsonPath("$.flats[2].flatNumber").value("103"))
            .andExpect(jsonPath("$.flats[2].done").value(true))

        acknowledge(owner101, versionId)
        acknowledge(tenant102, versionId)
        report("pending").andExpect(jsonPath("$.doneFlats").value(3)).andExpect(jsonPath("$.flats.length()").value(0))
        report("done").andExpect(jsonPath("$.flats.length()").value(3))

        getAs(owner101, "/api/policies/$policyId/acknowledgements").andExpect(status().isForbidden)
        report("everyone").andExpect(status().isBadRequest)
    }

    @Test
    fun `an owner of two flats accepts once for both, and counts as one person`() {
        val f104 = flatService.addFlat(wingService.listBySociety(societyId).single().id!!, "105").id!!
        userService.assignRole(owner103.id!!, AssignRoleCommand("RESIDENT", societyId, f104, ResidentType.OWNER))
        val (policyId, versionId) = publishedPolicy()
        currentPolicy = policyId
        acknowledge(owner103, versionId).andExpect(status().isOk)

        report("done")
            .andExpect(jsonPath("$.doneFlats").value(2))
            .andExpect(jsonPath("$.people").value(1))
            .andExpect(jsonPath("$.flats[*].flatNumber").value(org.hamcrest.Matchers.contains("103", "105")))
    }

    @Test
    fun `a minor change keeps acknowledgements, a material one asks again`() {
        val (policyId, firstVersion) = publishedPolicy()
        currentPolicy = policyId
        acknowledge(owner103, firstVersion).andExpect(status().isOk)

        val minor = publishChange(policyId, material = false)
        acknowledge(owner103, firstVersion).andExpect(status().isConflict) // no longer the current text
        report().andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.currentVersion").value(2))
            .andExpect(jsonPath("$.doneFlats").value(1))
        getAs(owner103, "/api/societies/$societyId/policies/pending-acknowledgements").andExpect(jsonPath("$.length()").value(0))
        acknowledge(owner103, minor).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))

        val material = publishChange(policyId, material = true)
        report().andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.doneFlats").value(0))
        getAs(owner103, "/api/societies/$societyId/policies/pending-acknowledgements").andExpect(jsonPath("$.length()").value(1))
        acknowledge(owner103, material).andExpect(jsonPath("$.version").value(3))
    }

    @Test
    fun `turning acknowledgement on after publication asks for it from then`() {
        val (policyId, versionId) = publishedPolicy(requiresAcknowledgement = false)
        mockMvc.perform(
            patch("/api/policies/$policyId")
                .header("Authorization", bearer(chairman))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"requiresAcknowledgement":true}""")
        ).andExpect(status().isOk)
        acknowledge(owner101, versionId).andExpect(status().isOk)
    }

    @Test
    fun `reminders go to pending flats, not again within the society's reminder days`() {
        val (policyId, versionId) = publishedPolicy()
        currentPolicy = policyId
        acknowledge(owner103, versionId)

        postAs(owner101, "/api/policies/$policyId/acknowledgements/remind").andExpect(status().isForbidden)
        postAs(committee, "/api/policies/$policyId/acknowledgements/remind")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reminded").value(2))
            .andExpect(jsonPath("$.skipped").value(0))
        postAs(committee, "/api/policies/$policyId/acknowledgements/remind")
            .andExpect(jsonPath("$.reminded").value(0))
            .andExpect(jsonPath("$.skipped").value(2))
        report().andExpect(jsonPath("$.flats[0].lastRemindedAt").exists())
            .andExpect(jsonPath("$.flats[2].lastRemindedAt").doesNotExist())

        // Eight days later (default 7): due again.
        entityManager.flush()
        jdbc.update("UPDATE policy_reminder SET sent_at = sent_at - interval '8 days'")
        entityManager.clear()
        postAs(committee, "/api/policies/$policyId/acknowledgements/remind").andExpect(jsonPath("$.reminded").value(2))
    }

    @Test
    fun `the committee exports one row per person`() {
        val (policyId, versionId) = publishedPolicy()
        acknowledge(tenant102, versionId)
        val csv = getAs(committee, "/api/policies/$policyId/acknowledgements.csv")
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .json()
        val lines = csv.trim().lines()
        assertEquals("Wing,Flat,Flat status,Name,Resident,Acknowledged at,Last reminded", lines[0])
        assertEquals(6, lines.size)
        assertContains(lines.single { it.contains("Dev") }, "A,102,Done,Dev,tenant,")
        assertContains(lines.single { it.contains("Esha") }, "A,103,Pending,Esha,owner,,")
    }

    @Test
    fun `an archived policy takes no acknowledgements`() {
        val (policyId, versionId) = publishedPolicy()
        postAs(chairman, "/api/policies/$policyId/archive").andExpect(status().isOk)
        acknowledge(owner101, versionId).andExpect(status().isConflict)
        getAs(committee, "/api/policies/$policyId/acknowledgements").andExpect(status().isConflict)
    }
}
