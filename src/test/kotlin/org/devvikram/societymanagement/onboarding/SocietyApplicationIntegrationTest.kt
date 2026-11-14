package org.devvikram.societymanagement.onboarding

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.society.model.SocietySource
import org.devvikram.societymanagement.society.model.SocietyStatus
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Both ways a society gets in (step O2): created by the super admin with a secretary invitation, or
// registered from the public site and approved. Same annotations as the other integration tests, so they
// share one context.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class SocietyApplicationIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService

    private lateinit var superAdmin: User
    // Each test registers from its own address, so the per-address limit never carries over.
    private lateinit var ip: String

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, society: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = society),
        )
    )

    @BeforeEach
    fun setUp() {
        superAdmin = user("SUPER_ADMIN")
        ip = "10.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"
    }

    private fun send(method: String, path: String, by: User?, body: String? = null): ResultActions {
        val builder = (if (method == "GET") get(path) else post(path)).with { it.remoteAddr = ip; it }
        by?.let { builder.header("Authorization", "Bearer ${jwtService.generateToken(it)}") }
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun idOf(result: ResultActions, path: String = "$.number") =
        JsonPath.read<Number>(result.andReturn().response.contentAsString, path).toLong()

    private fun application(phone: String, society: String = "Green Meadows ${(1000..9999).random()}") = """
        {"societyName":"$society","city":"Pune","address":"Baner Road","approxFlats":120,
         "applicantName":"Anita Desai","applicantPhone":"$phone","applicantEmail":"anita@example.com",
         "password":"chosen-pass-1","message":"Please call after 6 pm"}
    """.trimIndent()

    // ---- Platform-led ----

    @Test
    fun `the super admin creates a society with its secretary invitation, and the secretary joins`() {
        val phone = randomPhone()
        val created = send("POST", "/api/onboarding/societies", superAdmin,
            """{"name":"Sunrise Towers","secretary":{"name":"Ravi Kumar","phone":"$phone"}}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.society.status").value("SETUP"))
            .andExpect(jsonPath("$.society.source").value("PLATFORM"))
            .andExpect(jsonPath("$.secretaryInvitation.invitation.role").value("SECRETARY"))
            .andExpect(jsonPath("$.secretaryInvitation.invitation.phone").value(phone))
        val json = created.andReturn().response.contentAsString
        val societyId = JsonPath.read<Number>(json, "$.society.id").toLong()
        val token = JsonPath.read<String>(json, "$.secretaryInvitation.token")
        assert(JsonPath.read<String>(json, "$.secretaryInvitation.inviteUrl").endsWith("/invite/$token"))

        send("POST", "/api/invitations/token/$token/accept", null, """{"password":"secretary-pass"}""")
            .andExpect(status().isOk)
        val secretary = assertNotNull(userService.getByPhone(phone))
        assertEquals(
            listOf("SECRETARY" to societyId),
            userService.getRoles(secretary.id!!).map { it.role.code to it.society?.id },
        )
    }

    @Test
    fun `only the platform creates societies, and a secretary is required`() {
        val other = societyService.createSociety("Other Society").id!!
        val secretary = user("SECRETARY", other)
        send("POST", "/api/onboarding/societies", secretary,
            """{"name":"Sneaky","secretary":{"name":"X","phone":"${randomPhone()}"}}""")
            .andExpect(status().isForbidden)
        send("POST", "/api/onboarding/societies", superAdmin, """{"name":"No Secretary"}""")
            .andExpect(status().isBadRequest)
        send("POST", "/api/onboarding/societies", superAdmin, """{"name":"Bad Phone","secretary":{"name":"X","phone":"12"}}""")
            .andExpect(status().isBadRequest)
    }

    // ---- Self-signup ----

    @Test
    fun `registration is open and needs no sign-in`() {
        send("GET", "/api/society-applications/public/enabled", null)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
    }

    @Test
    fun `approving a registration creates the society, the account and the secretary role together`() {
        val phone = randomPhone()
        val number = idOf(
            send("POST", "/api/society-applications/public", null, application(phone, "Lake View"))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value("PENDING"))
        )
        // Nothing exists yet.
        assertNull(userService.getByPhone(phone))
        send("GET", "/api/society-applications/public/status?number=$number&phone=$phone", null)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.societyName").value("Lake View"))
            .andExpect(jsonPath("$.status").value("PENDING"))
        send("GET", "/api/society-applications/public/status?number=$number&phone=${randomPhone()}", null)
            .andExpect(status().isNotFound)

        send("GET", "/api/society-applications", superAdmin)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.id == $number)].applicantName").value("Anita Desai"))
            .andExpect(jsonPath("$[?(@.id == $number)].existingAccount").value(false))

        val approved = send("POST", "/api/society-applications/$number/approve", superAdmin)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.reviewedByName").value(superAdmin.name))
        val societyId = idOf(approved, "$.societyId")

        val society = societyService.getById(societyId)
        assertEquals("Lake View", society.name)
        assertEquals(SocietyStatus.SETUP, society.status)
        assertEquals(SocietySource.SELF_SIGNUP, society.source)
        assertEquals("Pune", society.city)
        assertEquals(phone, society.contactPhone)

        val account = assertNotNull(userService.getByPhone(phone))
        assertEquals("Anita Desai", account.name)
        assertEquals(listOf("SECRETARY" to societyId), userService.getRoles(account.id!!).map { it.role.code to it.society?.id })
        // The password chosen on the form signs them in.
        send("POST", "/api/auth/login", null, """{"phone":"$phone","password":"chosen-pass-1"}""")
            .andExpect(status().isOk)

        send("GET", "/api/society-applications/public/status?number=$number&phone=$phone", null)
            .andExpect(jsonPath("$.status").value("APPROVED"))
        send("POST", "/api/society-applications/$number/approve", superAdmin)
            .andExpect(status().isConflict)
    }

    @Test
    fun `a signed-in user registers with their own account`() {
        val member = user("COMMITTEE", societyService.createSociety("Where I Live").id!!)
        val number = idOf(
            send("POST", "/api/society-applications/public", member, """{"societyName":"My Second Society","city":"Nashik"}""")
                .andExpect(status().isCreated)
        )
        send("GET", "/api/society-applications/$number", superAdmin)
            .andExpect(jsonPath("$.applicantPhone").value(member.phone))
            .andExpect(jsonPath("$.existingAccount").value(true))

        val societyId = idOf(send("POST", "/api/society-applications/$number/approve", superAdmin).andExpect(status().isOk), "$.societyId")
        val roles = userService.getRoles(member.id!!).map { it.role.code to it.society?.id }
        assert(("SECRETARY" to societyId) in roles) { "Expected SECRETARY in $roles" }
        assertEquals(2, roles.size)
    }

    @Test
    fun `new applicants need a name, phone and password, and an existing phone must sign in`() {
        send("POST", "/api/society-applications/public", null, """{"societyName":"Half Filled","city":"Pune"}""")
            .andExpect(status().isBadRequest)
        send("POST", "/api/society-applications/public", null,
            """{"societyName":"Short","city":"Pune","applicantName":"A","applicantPhone":"${randomPhone()}","password":"short"}""")
            .andExpect(status().isBadRequest)
        val existing = user("SUPER_ADMIN")
        send("POST", "/api/society-applications/public", null, application(existing.phone))
            .andExpect(status().isConflict)
    }

    @Test
    fun `one pending registration per phone, and a rejected applicant sees the reason and may apply again`() {
        val phone = randomPhone()
        val number = idOf(send("POST", "/api/society-applications/public", null, application(phone)).andExpect(status().isCreated))
        send("POST", "/api/society-applications/public", null, application(phone))
            .andExpect(status().isConflict)

        send("POST", "/api/society-applications/$number/reject", superAdmin, """{"reason":" "}""")
            .andExpect(status().isBadRequest)
        send("POST", "/api/society-applications/$number/reject", superAdmin, """{"reason":"Could not reach you on this number"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REJECTED"))
        send("GET", "/api/society-applications/public/status?number=$number&phone=$phone", null)
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.reason").value("Could not reach you on this number"))
        send("POST", "/api/society-applications/$number/approve", superAdmin)
            .andExpect(status().isConflict)
        assertNull(userService.getByPhone(phone))

        send("POST", "/api/society-applications/public", null, application(phone))
            .andExpect(status().isCreated)
    }

    @Test
    fun `approval is refused, creating nothing, when the phone has signed up since`() {
        val phone = randomPhone()
        val name = "Refused Society ${(1000..9999).random()}"
        val number = idOf(send("POST", "/api/society-applications/public", null, application(phone, name)).andExpect(status().isCreated))
        userService.createUser(CreateUserCommand("Someone", phone, rawPassword = "secret123", role = AssignRoleCommand("SUPER_ADMIN")))

        send("POST", "/api/society-applications/$number/approve", superAdmin)
            .andExpect(status().isConflict)
        assert(societyService.getAll().none { it.name == name })
    }

    @Test
    fun `registrations from one address are limited per hour`() {
        repeat(5) {
            send("POST", "/api/society-applications/public", null, application(randomPhone())).andExpect(status().isCreated)
        }
        send("POST", "/api/society-applications/public", null, application(randomPhone()))
            .andExpect(status().isTooManyRequests)
    }

    @Test
    fun `only the platform sees and decides registrations`() {
        val number = idOf(send("POST", "/api/society-applications/public", null, application(randomPhone())).andExpect(status().isCreated))
        val secretary = user("SECRETARY", societyService.createSociety("Some Society").id!!)
        send("GET", "/api/society-applications", secretary).andExpect(status().isForbidden)
        send("POST", "/api/society-applications/$number/approve", secretary).andExpect(status().isForbidden)
        send("GET", "/api/society-applications", null).andExpect(status().isUnauthorized)
    }
}
