package org.devvikram.societymanagement.invitation

import com.jayway.jsonpath.JsonPath
import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.invitation.model.Invitation
import org.devvikram.societymanagement.invitation.service.InvitationAccepted
import org.devvikram.societymanagement.invitation.service.InvitationNotificationListener
import org.devvikram.societymanagement.invitation.service.InvitationSent
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
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.startsWith
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
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Same annotations and imported config as the maintenance integration tests, so they share one context.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class InvitationIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var settingsService: SocietySettingsService
    @Autowired lateinit var pushNotifier: RecordingPushNotifier
    @Autowired lateinit var entityManager: EntityManager
    @Autowired lateinit var notificationListener: InvitationNotificationListener

    private var societyId = 0L
    private var otherSocietyId = 0L
    private var flatId = 0L
    private var otherFlatId = 0L
    private lateinit var superAdmin: User
    private lateinit var secretary: User
    private lateinit var committee: User
    private lateinit var resident: User
    private lateinit var otherSecretary: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, society: Long? = societyId, flat: Long? = null, type: ResidentType? = null) =
        userService.createUser(
            CreateUserCommand(
                name = "$roleCode user",
                phone = randomPhone(),
                rawPassword = "secret123",
                role = AssignRoleCommand(roleCode = roleCode, societyId = society, flatId = flat, residentType = type),
            )
        )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Invite Test Society").id!!
        flatId = flatService.addFlat(wingService.addWing(societyId, "A").id!!, "101", BigDecimal("900")).id!!
        otherSocietyId = societyService.createSociety("Other Invite Society").id!!
        otherFlatId = flatService.addFlat(wingService.addWing(otherSocietyId, "B").id!!, "201", null).id!!
        superAdmin = user("SUPER_ADMIN", society = null)
        secretary = user("SECRETARY")
        committee = user("COMMITTEE")
        resident = user("RESIDENT", flat = flatId, type = ResidentType.OWNER)
        otherSecretary = user("SECRETARY", society = otherSocietyId)
        pushNotifier.sent.clear()
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun call(method: String, path: String, by: User? = null, body: String? = null): ResultActions {
        val builder = if (method == "GET") get(path) else post(path)
        if (by != null) builder.header("Authorization", bearer(by))
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun residentInvite(phone: String, type: String = "OWNER", flat: Long = flatId) =
        """{"phone":"$phone","name":"Priya","role":"RESIDENT","flatId":$flat,"residentType":"$type"}"""

    private fun roleInvite(phone: String, role: String) = """{"phone":"$phone","name":"Arjun","role":"$role"}"""

    private fun invite(by: User, body: String, society: Long = societyId) =
        call("POST", "/api/societies/$society/invitations", by, body)

    private fun json(result: ResultActions): String = result.andReturn().response.contentAsString

    // Token and invitation id of a successful invite.
    private fun issued(result: ResultActions): Pair<String, Long> {
        val body = json(result.andExpect(status().isCreated))
        return JsonPath.read<String>(body, "$.token") to JsonPath.read<Number>(body, "$.invitation.id").toLong()
    }

    @Test
    fun `a secretary invites a new resident who signs up from the link`() {
        val phone = randomPhone()
        val result = invite(secretary, residentInvite(phone))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.invitation.status").value("PENDING"))
            .andExpect(jsonPath("$.invitation.flatLabel").value("A-101"))
            .andExpect(jsonPath("$.invitation.residentType").value("OWNER"))
            .andExpect(jsonPath("$.inviteUrl").value(startsWith("http://localhost:4200/invite/")))
        val (token, _) = issued(result)
        val expiresAt = Instant.parse(JsonPath.read(json(result), "$.invitation.expiresAt"))
        assertTrue(Duration.between(Instant.now(), expiresAt) > Duration.ofDays(6))

        call("GET", "/api/invitations/token/$token")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.societyName").value("Invite Test Society"))
            .andExpect(jsonPath("$.roleName").value("Resident"))
            .andExpect(jsonPath("$.flatLabel").value("A-101"))
            .andExpect(jsonPath("$.maskedPhone").value(phone.take(2) + "•••••" + phone.takeLast(3)))
            .andExpect(jsonPath("$.invitedByName").value("SECRETARY user"))
            .andExpect(jsonPath("$.hasAccount").value(false))

        call("POST", "/api/invitations/token/$token/accept", body = "{}").andExpect(status().isBadRequest)
        call("POST", "/api/invitations/token/$token/accept", body = """{"name":"Priya Shah","password":"short"}""")
            .andExpect(status().isBadRequest)
        call("POST", "/api/invitations/token/$token/accept", body = """{"name":"Priya Shah","password":"welcome123"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACCEPTED"))

        val joined = userService.getByPhone(phone)!!
        assertEquals("Priya Shah", joined.name)
        val role = userService.getRoles(joined.id!!).single()
        assertEquals("RESIDENT", role.role.code)
        assertEquals(flatId, role.flat?.id)
        assertEquals(ResidentType.OWNER, role.residentType)
        call("POST", "/api/auth/login", body = """{"phone":"$phone","password":"welcome123"}""").andExpect(status().isOk)

        call("POST", "/api/invitations/token/$token/accept", body = """{"password":"welcome123"}""")
            .andExpect(status().isConflict)
        call("GET", "/api/invitations/token/$token").andExpect(jsonPath("$.status").value("ACCEPTED"))
    }

    @Test
    fun `only roles below your own, in your own society`() {
        invite(committee, residentInvite(randomPhone())).andExpect(status().isCreated)
        invite(committee, roleInvite(randomPhone(), "GUARD")).andExpect(status().isForbidden)
        invite(committee, roleInvite(randomPhone(), "COMMITTEE")).andExpect(status().isForbidden)
        invite(secretary, roleInvite(randomPhone(), "COMMITTEE")).andExpect(status().isCreated)
        invite(secretary, roleInvite(randomPhone(), "GUARD")).andExpect(status().isCreated)
        invite(secretary, roleInvite(randomPhone(), "SECRETARY")).andExpect(status().isForbidden)
        invite(resident, residentInvite(randomPhone(), "FAMILY")).andExpect(status().isForbidden)
        invite(otherSecretary, residentInvite(randomPhone())).andExpect(status().isForbidden)
        invite(superAdmin, roleInvite(randomPhone(), "ADMIN")).andExpect(status().isCreated)
    }

    @Test
    fun `an invitation must match its role`() {
        invite(secretary, roleInvite(randomPhone(), "RESIDENT")).andExpect(status().isBadRequest)
        invite(secretary, residentInvite(randomPhone(), flat = otherFlatId)).andExpect(status().isBadRequest)
        invite(secretary, """{"phone":"${randomPhone()}","name":"X","role":"GUARD","flatId":$flatId}""")
            .andExpect(status().isBadRequest)
        invite(secretary, residentInvite("12345")).andExpect(status().isBadRequest)
        invite(superAdmin, roleInvite(randomPhone(), "SUPER_ADMIN")).andExpect(status().isBadRequest)
        invite(secretary, roleInvite(committee.phone, "COMMITTEE")).andExpect(status().isConflict)
    }

    @Test
    fun `someone with an account accepts after logging in`() {
        val (token, id) = issued(invite(secretary, roleInvite(resident.phone, "COMMITTEE")))

        call("GET", "/api/invitations/token/$token").andExpect(jsonPath("$.hasAccount").value(true))
        call("POST", "/api/invitations/token/$token/accept", body = """{"password":"welcome123"}""")
            .andExpect(status().isConflict)
        call("POST", "/api/invitations/token/$token/accept", by = committee).andExpect(status().isForbidden)
        call("POST", "/api/invitations/$id/accept", by = committee).andExpect(status().isForbidden)

        call("GET", "/api/invitations/mine", by = resident)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].role").value("COMMITTEE"))
        call("POST", "/api/invitations/$id/accept", by = resident)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
        assertTrue(userService.getRoles(resident.id!!).any { it.role.code == "COMMITTEE" && it.society?.id == societyId })
        call("GET", "/api/invitations/mine", by = resident).andExpect(jsonPath("$", hasSize<Any>(0)))
    }

    // The listeners run after commit, which a rolled-back test never reaches, so they're called directly.
    @Test
    fun `pushes go to an invited account holder and back to the inviter`() {
        userService.updateProfile(resident.id!!, UpdateProfileCommand(fcmToken = "resident-token"))
        userService.updateProfile(secretary.id!!, UpdateProfileCommand(fcmToken = "secretary-token"))

        notificationListener.onSent(InvitationSent(5L, resident.phone, "Sneha", "Committee Member", "Invite Test Society"))
        notificationListener.onSent(InvitationSent(6L, randomPhone(), "Sneha", "Resident", "Invite Test Society"))
        notificationListener.onAccepted(InvitationAccepted(5L, secretary.id!!, "Priya", "Committee Member", "Invite Test Society"))

        assertEquals(2, pushNotifier.sent.size)
        val (sent, accepted) = pushNotifier.sent
        assertEquals(listOf("resident-token"), sent.tokens.toList())
        assertEquals("You're invited to Invite Test Society", sent.title)
        assertEquals("INVITATION_RECEIVED", sent.data["type"])
        assertEquals(listOf("secretary-token"), accepted.tokens.toList())
        assertEquals("Priya joined Invite Test Society as Committee Member", accepted.body)
    }

    @Test
    fun `inviting the same person again replaces the open invitation`() {
        val phone = randomPhone()
        val (first, _) = issued(invite(secretary, residentInvite(phone)))
        val (second, _) = issued(invite(committee, residentInvite(phone)))
        call("GET", "/api/invitations/token/$first").andExpect(jsonPath("$.status").value("REVOKED"))
        call("POST", "/api/invitations/token/$first/accept", body = """{"password":"welcome123"}""")
            .andExpect(status().isConflict)
        call("GET", "/api/invitations/token/$second").andExpect(jsonPath("$.status").value("PENDING"))
        // Same person, role and flat with another resident type: also a re-invite.
        issued(invite(secretary, residentInvite(phone, type = "TENANT")))
        call("GET", "/api/invitations/token/$second").andExpect(jsonPath("$.status").value("REVOKED"))
    }

    @Test
    fun `revoke, resend and decline`() {
        val (token, id) = issued(invite(secretary, residentInvite(randomPhone())))

        call("POST", "/api/invitations/$id/revoke", by = otherSecretary).andExpect(status().isForbidden)
        val resent = call("POST", "/api/invitations/$id/resend", by = committee).andExpect(status().isOk)
        val newToken = JsonPath.read<String>(json(resent), "$.token")
        assertTrue(newToken != token)
        call("GET", "/api/invitations/token/$token").andExpect(status().isNotFound)
        call("GET", "/api/invitations/token/$newToken").andExpect(jsonPath("$.status").value("PENDING"))

        call("POST", "/api/invitations/$id/revoke", by = committee)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REVOKED"))
        call("POST", "/api/invitations/$id/revoke", by = secretary).andExpect(status().isConflict)
        call("POST", "/api/invitations/$id/resend", by = secretary).andExpect(status().isConflict)

        val (other, _) = issued(invite(secretary, residentInvite(randomPhone())))
        call("POST", "/api/invitations/token/$other/decline").andExpect(jsonPath("$.status").value("DECLINED"))
        call("POST", "/api/invitations/token/$other/decline").andExpect(status().isConflict)
    }

    @Test
    fun `an expired link stops working until it is resent`() {
        settingsService.update(societyId, mapOf(SocietySettingKeys.INVITE_EXPIRY_DAYS to 2), secretary.id!!)
        val result = invite(secretary, residentInvite(randomPhone()))
        val expiresAt = Instant.parse(JsonPath.read(json(result), "$.invitation.expiresAt"))
        assertTrue(Duration.between(Instant.now(), expiresAt) < Duration.ofDays(2).plusMinutes(1))
        val (token, id) = issued(result)

        // Through JPA, so the instant is written the way the app writes it (the column has no time zone).
        entityManager.find(Invitation::class.java, id).expiresAt = Instant.now().minus(Duration.ofHours(1))
        entityManager.flush()
        entityManager.clear()

        call("GET", "/api/invitations/token/$token").andExpect(jsonPath("$.status").value("EXPIRED"))
        call("POST", "/api/invitations/token/$token/accept", body = """{"password":"welcome123"}""")
            .andExpect(status().isConflict)
        call("GET", "/api/societies/$societyId/invitations?status=EXPIRED", by = secretary)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
        call("GET", "/api/societies/$societyId/invitations?status=PENDING", by = secretary)
            .andExpect(jsonPath("$", hasSize<Any>(0)))

        val resent = call("POST", "/api/invitations/$id/resend", by = secretary).andExpect(status().isOk)
        call("GET", "/api/invitations/token/${JsonPath.read<String>(json(resent), "$.token")}")
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    @Test
    fun `an invitation fails if the inviter lost the right to give the role`() {
        val (token, _) = issued(invite(committee, residentInvite(randomPhone())))
        val assignment = userService.getRoles(committee.id!!).single()
        userService.removeRole(committee.id!!, assignment.id!!)
        call("POST", "/api/invitations/token/$token/accept", body = """{"password":"welcome123"}""")
            .andExpect(status().isConflict)
    }

    @Test
    fun `the committee lists the society's invitations, residents can't`() {
        issued(invite(secretary, residentInvite(randomPhone())))
        val (token, _) = issued(invite(secretary, roleInvite(randomPhone(), "GUARD")))
        call("POST", "/api/invitations/token/$token/decline")

        call("GET", "/api/societies/$societyId/invitations", by = committee)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].role").value("GUARD"))
        call("GET", "/api/societies/$societyId/invitations?status=DECLINED", by = committee)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
        call("GET", "/api/societies/$societyId/invitations", by = resident).andExpect(status().isForbidden)
        call("GET", "/api/societies/$societyId/invitations", by = otherSecretary).andExpect(status().isForbidden)
    }
}
