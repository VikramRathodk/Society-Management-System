package org.devvikram.societymanagement.invitation

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.invitation.service.InvitationDecided
import org.devvikram.societymanagement.invitation.service.InvitationNeedsApproval
import org.devvikram.societymanagement.invitation.service.InvitationNotificationListener
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.maintenance.RecordingPushNotifier
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
import org.hamcrest.Matchers.contains
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Residents inviting family and tenants onto their own flat (step 4). Same annotations and imported config
// as the other integration tests, so they share one context.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class ResidentInvitationIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var settingsService: SocietySettingsService
    @Autowired lateinit var notificationListener: InvitationNotificationListener
    @Autowired lateinit var pushNotifier: RecordingPushNotifier

    private var societyId = 0L
    private var flatId = 0L
    private var otherFlatId = 0L
    private lateinit var owner: User
    private lateinit var family: User
    private lateinit var tenant: User
    private lateinit var neighbour: User
    private lateinit var committee: User
    private lateinit var secretary: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, flat: Long? = null, type: ResidentType? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode ${type ?: ""}".trim(),
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = societyId, flatId = flat, residentType = type),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Resident Invite Society").id!!
        val wingId = wingService.addWing(societyId, "A").id!!
        flatId = flatService.addFlat(wingId, "101", BigDecimal("900")).id!!
        otherFlatId = flatService.addFlat(wingId, "102", BigDecimal("900")).id!!
        owner = user("RESIDENT", flatId, ResidentType.OWNER)
        family = user("RESIDENT", flatId, ResidentType.FAMILY)
        tenant = user("RESIDENT", flatId, ResidentType.TENANT)
        neighbour = user("RESIDENT", otherFlatId, ResidentType.OWNER)
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")
        pushNotifier.sent.clear()
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun call(method: String, path: String, by: User? = null, body: String? = null): ResultActions {
        val builder = if (method == "GET") get(path) else post(path)
        if (by != null) builder.header("Authorization", bearer(by))
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun invite(by: User, type: String, flat: Long = flatId, role: String = "RESIDENT") =
        call("POST", "/api/societies/$societyId/invitations", by,
            """{"phone":"${randomPhone()}","name":"Guest","role":"$role","flatId":$flat,"residentType":"$type"}""")

    private fun issued(result: ResultActions): Pair<String, Long> {
        val body = result.andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read<String>(body, "$.token") to JsonPath.read<Number>(body, "$.invitation.id").toLong()
    }

    private fun setting(key: String, value: Any) = settingsService.update(societyId, mapOf(key to value), secretary.id!!)

    @Test
    fun `an owner's invitation works once the committee approves it`() {
        val (token, id) = issued(
            invite(owner, "TENANT").andExpect(jsonPath("$.invitation.status").value("AWAITING_APPROVAL"))
        )
        call("GET", "/api/invitations/token/$token").andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
        call("POST", "/api/invitations/token/$token/accept", body = """{"password":"welcome123"}""")
            .andExpect(status().isConflict)

        call("POST", "/api/invitations/$id/approve", by = owner).andExpect(status().isForbidden)
        call("POST", "/api/invitations/$id/approve", by = committee)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.approvedByName").value("COMMITTEE"))
        call("POST", "/api/invitations/$id/approve", by = committee).andExpect(status().isConflict)

        call("POST", "/api/invitations/token/$token/accept", body = """{"password":"welcome123"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
    }

    @Test
    fun `residents invite only onto their own flat, by their resident type`() {
        invite(owner, "FAMILY").andExpect(status().isCreated)
        invite(owner, "TENANT").andExpect(status().isCreated)
        invite(owner, "OWNER").andExpect(status().isForbidden)
        invite(owner, "TENANT", flat = otherFlatId).andExpect(status().isForbidden)
        invite(tenant, "TENANT").andExpect(status().isCreated)
        invite(tenant, "FAMILY").andExpect(status().isForbidden)
        invite(family, "FAMILY").andExpect(status().isForbidden)
        call("POST", "/api/societies/$societyId/invitations", owner, """{"phone":"${randomPhone()}","name":"G","role":"GUARD"}""")
            .andExpect(status().isForbidden)
    }

    @Test
    fun `the flats a resident may invite to`() {
        call("GET", "/api/societies/$societyId/invitations/flat-options", by = owner)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].flatId").value(flatId))
            .andExpect(jsonPath("$[0].flatLabel").value("A-101"))
            .andExpect(jsonPath("$[0].role").value("RESIDENT"))
            .andExpect(jsonPath("$[0].residentTypes").value(contains("FAMILY", "TENANT")))
            .andExpect(jsonPath("$[0].needsApproval").value(true))
        call("GET", "/api/societies/$societyId/invitations/flat-options", by = tenant)
            .andExpect(jsonPath("$[0].residentTypes").value(contains("TENANT")))
        call("GET", "/api/societies/$societyId/invitations/flat-options", by = family).andExpect(jsonPath("$", hasSize<Any>(0)))
        call("GET", "/api/societies/$societyId/invitations/flat-options", by = committee).andExpect(jsonPath("$", hasSize<Any>(0)))

        setting(SocietySettingKeys.RESIDENT_INVITES_NEED_APPROVAL, false)
        call("GET", "/api/societies/$societyId/invitations/flat-options", by = owner)
            .andExpect(jsonPath("$[0].needsApproval").value(false))
        setting(SocietySettingKeys.RESIDENTS_CAN_INVITE, false)
        call("GET", "/api/societies/$societyId/invitations/flat-options", by = owner).andExpect(jsonPath("$", hasSize<Any>(0)))
    }

    @Test
    fun `without the approval setting a resident's invitation works straight away`() {
        setting(SocietySettingKeys.RESIDENT_INVITES_NEED_APPROVAL, false)
        invite(owner, "FAMILY").andExpect(jsonPath("$.invitation.status").value("PENDING"))
        // Staff invitations never need approval.
        setting(SocietySettingKeys.RESIDENT_INVITES_NEED_APPROVAL, true)
        invite(committee, "TENANT").andExpect(jsonPath("$.invitation.status").value("PENDING"))
    }

    @Test
    fun `turning resident invitations off stops new ones and open ones`() {
        val (_, waiting) = issued(invite(owner, "FAMILY"))
        setting(SocietySettingKeys.RESIDENTS_CAN_INVITE, false)
        invite(owner, "FAMILY").andExpect(status().isForbidden)
        call("POST", "/api/invitations/$waiting/approve", by = committee).andExpect(status().isConflict)
        invite(committee, "FAMILY").andExpect(status().isCreated)
    }

    @Test
    fun `a flat holds at most the society's number of residents and open invitations`() {
        setting(SocietySettingKeys.MAX_RESIDENTS_PER_FLAT, 4) // flat 101 has 3 residents
        invite(owner, "FAMILY").andExpect(status().isCreated)
        invite(owner, "FAMILY").andExpect(status().isConflict)
        invite(committee, "TENANT").andExpect(status().isConflict)
        invite(committee, "TENANT", flat = otherFlatId).andExpect(status().isCreated)
        setting(SocietySettingKeys.MAX_RESIDENTS_PER_FLAT, 0)
        invite(owner, "FAMILY").andExpect(status().isCreated)
    }

    @Test
    fun `the committee rejects, and a rejected link never works`() {
        val (token, id) = issued(invite(owner, "TENANT"))
        call("POST", "/api/invitations/$id/reject", by = tenant).andExpect(status().isForbidden)
        call("POST", "/api/invitations/$id/reject", by = committee)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REJECTED"))
        call("POST", "/api/invitations/$id/reject", by = committee).andExpect(status().isConflict)
        call("GET", "/api/invitations/token/$token").andExpect(jsonPath("$.status").value("REJECTED"))
        call("POST", "/api/invitations/$id/resend", by = owner).andExpect(status().isConflict)
    }

    @Test
    fun `residents see and manage only the invitations they sent`() {
        val (_, ownInvite) = issued(invite(owner, "FAMILY"))
        val (_, tenantInvite) = issued(invite(tenant, "TENANT"))

        call("GET", "/api/societies/$societyId/invitations", by = owner)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].id").value(ownInvite))
        call("GET", "/api/societies/$societyId/invitations?status=AWAITING_APPROVAL", by = committee)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
        call("GET", "/api/invitations/$tenantInvite", by = owner).andExpect(status().isForbidden)
        call("GET", "/api/invitations/$ownInvite", by = owner).andExpect(status().isOk)
        call("GET", "/api/societies/$societyId/invitations", by = user("GUARD")).andExpect(status().isOk)

        // Resend keeps it waiting for approval with a new link; the sender can cancel it.
        call("POST", "/api/invitations/$ownInvite/resend", by = owner)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.invitation.status").value("AWAITING_APPROVAL"))
        call("POST", "/api/invitations/$tenantInvite/revoke", by = owner).andExpect(status().isForbidden)
        call("POST", "/api/invitations/$ownInvite/revoke", by = owner)
            .andExpect(jsonPath("$.status").value("REVOKED"))
    }

    @Test
    fun `a resident sees their society by name without permission to view its settings`() {
        call("GET", "/api/societies/$societyId", by = owner).andExpect(status().isForbidden)
        call("GET", "/api/users/me", by = owner)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.user.roles[0].societyName").value("Resident Invite Society"))
    }

    // Listeners run after commit, which a rolled-back test never reaches, so they're called directly.
    @Test
    fun `approval pushes go to the committee, decisions back to the resident`() {
        listOf(owner to "owner-token", committee to "committee-token", secretary to "secretary-token", tenant to "tenant-token")
            .forEach { (u, t) -> userService.updateProfile(u.id!!, UpdateProfileCommand(fcmToken = t)) }

        notificationListener.onNeedsApproval(InvitationNeedsApproval(5L, societyId, "Tony", "Pepper", "A-101"))
        notificationListener.onDecided(InvitationDecided(5L, owner.id!!, "Pepper", "Resident Invite Society", approved = false))

        val (needs, decided) = pushNotifier.sent
        assertEquals(setOf("committee-token", "secretary-token"), needs.tokens.toSet())
        assertEquals("Tony invited Pepper to flat A-101", needs.body)
        assertEquals("INVITATION_NEEDS_APPROVAL", needs.data["type"])
        assertEquals(listOf("owner-token"), decided.tokens.toList())
        assertEquals("INVITATION_REJECTED", decided.data["type"])
        assertTrue(decided.body.contains("didn't approve"))
    }
}
