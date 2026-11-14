package org.devvikram.societymanagement.onboarding

import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.society.model.SocietyStatus
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
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

// Same annotations and imported config as the other integration tests, so they share one context.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class OnboardingIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService

    private lateinit var superAdmin: User
    private var liveSocietyId = 0L

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, society: Long? = null, flat: Long? = null, type: ResidentType? = null) =
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
        superAdmin = user("SUPER_ADMIN")
        liveSocietyId = societyService.createSociety("Already Live Society").id!!
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun send(method: String, path: String, by: User, body: String? = null): ResultActions {
        val builder = when (method) {
            "GET" -> get(path)
            "PATCH" -> patch(path)
            else -> post(path)
        }.header("Authorization", bearer(by))
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun createSociety(body: String = """{"name":"New Heights"}"""): Long {
        val json = send("POST", "/api/societies", superAdmin, body)
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return com.jayway.jsonpath.JsonPath.read<Number>(json, "$.id").toLong()
    }

    private val completeProfile = """
        {"address":"Plot 7, MG Road","city":"Pune","state":"Maharashtra","pincode":"411001",
         "contactPhone":"9000000001","registrationNumber":"PNA/HSG/1234","notes":"Water tank cleaning every April"}
    """.trimIndent()

    @Test
    fun `a society created through the API starts in setup, or live when asked`() {
        val setup = createSociety()
        send("GET", "/api/societies/$setup", superAdmin)
            .andExpect(jsonPath("$.status").value("SETUP"))
            .andExpect(jsonPath("$.source").value("PLATFORM"))
        val live = createSociety("""{"name":"Moved In","startLive":true}""")
        send("GET", "/api/societies/$live", superAdmin).andExpect(jsonPath("$.status").value("LIVE"))
    }

    @Test
    fun `the checklist follows the steps and going live needs the profile`() {
        val societyId = createSociety()
        val secretary = user("SECRETARY", society = societyId)

        send("GET", "/api/societies/$societyId/onboarding", secretary)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SETUP"))
            .andExpect(jsonPath("$.profile.done").value(false))
            .andExpect(jsonPath("$.profile.missing.length()").value(5))
            .andExpect(jsonPath("$.committee.members").value(1))
            .andExpect(jsonPath("$.committee.done").value(false))
            .andExpect(jsonPath("$.canGoLive").value(false))
            .andExpect(jsonPath("$.stepsDone").value(0))
        send("POST", "/api/societies/$societyId/go-live", secretary)
            .andExpect(status().isBadRequest)

        send("PATCH", "/api/societies/$societyId", secretary, completeProfile)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.city").value("Pune"))
            .andExpect(jsonPath("$.notes").value("Water tank cleaning every April"))
        flatService.addFlat(wingService.addWing(societyId, "A").id!!, "101", null)
        send("POST", "/api/societies/$societyId/invitations", secretary,
            """{"phone":"${randomPhone()}","name":"Meera","role":"COMMITTEE"}""")
            .andExpect(status().isCreated)

        send("GET", "/api/societies/$societyId/onboarding", secretary)
            .andExpect(jsonPath("$.profile.done").value(true))
            .andExpect(jsonPath("$.structure.flats").value(1))
            .andExpect(jsonPath("$.committee.openInvitations").value(1))
            .andExpect(jsonPath("$.committee.done").value(true))
            .andExpect(jsonPath("$.stepsDone").value(3))
            .andExpect(jsonPath("$.canGoLive").value(true))

        send("POST", "/api/societies/$societyId/go-live", secretary)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.onboarding.status").value("LIVE"))
            .andExpect(jsonPath("$.onboarding.wentLiveAt").isNotEmpty)
            .andExpect(jsonPath("$.onboarding.canGoLive").value(false))
            .andExpect(jsonPath("$.residentInvitations").doesNotExist())
        send("POST", "/api/societies/$societyId/go-live", secretary).andExpect(status().isConflict)
    }

    @Test
    fun `during setup nobody can be given a flat, by account or invitation, until it goes live`() {
        val societyId = createSociety()
        val secretary = user("SECRETARY", society = societyId)
        val flatId = flatService.addFlat(wingService.addWing(societyId, "A").id!!, "101", null).id!!

        assertThrows<ConflictException> { user("RESIDENT", society = societyId, flat = flatId, type = ResidentType.OWNER) }
        send("POST", "/api/societies/$societyId/invitations", secretary,
            """{"phone":"${randomPhone()}","name":"Priya","role":"RESIDENT","flatId":$flatId,"residentType":"OWNER"}""")
            .andExpect(status().isConflict)
        // Society-wide roles are fine: the committee sets the society up.
        user("GUARD", society = societyId)

        send("PATCH", "/api/societies/$societyId", secretary, completeProfile).andExpect(status().isOk)
        send("POST", "/api/societies/$societyId/go-live", secretary).andExpect(status().isOk)
        val resident = user("RESIDENT", society = societyId, flat = flatId, type = ResidentType.OWNER)
        assertEquals(1, userService.getRoles(resident.id!!).size)
        assertEquals(SocietyStatus.LIVE, societyService.getById(societyId).status)
    }

    @Test
    fun `profile edits are validated and need the society's update permission`() {
        val committee = user("COMMITTEE", society = liveSocietyId)
        val secretary = user("SECRETARY", society = liveSocietyId)
        val outsider = user("SECRETARY", society = createSociety())

        send("PATCH", "/api/societies/$liveSocietyId", secretary, """{"pincode":"12345"}""").andExpect(status().isBadRequest)
        send("PATCH", "/api/societies/$liveSocietyId", secretary, """{"contactEmail":"not-an-email"}""").andExpect(status().isBadRequest)
        send("PATCH", "/api/societies/$liveSocietyId", committee, """{"city":"Pune"}""").andExpect(status().isForbidden)
        send("PATCH", "/api/societies/$liveSocietyId", outsider, """{"city":"Pune"}""").andExpect(status().isForbidden)
        send("GET", "/api/societies/$liveSocietyId/onboarding", outsider).andExpect(status().isForbidden)
        send("PATCH", "/api/societies/$liveSocietyId", secretary, """{"city":" Pune ","pincode":""}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.city").value("Pune"))
            .andExpect(jsonPath("$.pincode").doesNotExist())
    }

    @Test
    fun `a secretary uploads, reads and removes the logo`() {
        val secretary = user("SECRETARY", society = liveSocietyId)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val upload = multipart("/api/societies/$liveSocietyId/logo")
            .file(MockMultipartFile("file", "logo.png", "image/png", png))
            .with { it.method = "PUT"; it }
            .header("Authorization", bearer(secretary))
        mockMvc.perform(upload).andExpect(status().isOk).andExpect(jsonPath("$.hasLogo").value(true))

        mockMvc.perform(get("/api/societies/$liveSocietyId/logo").header("Authorization", bearer(secretary)))
            .andExpect(status().isOk)
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(png))

        val text = multipart("/api/societies/$liveSocietyId/logo")
            .file(MockMultipartFile("file", "logo.txt", "image/png", "hello".toByteArray()))
            .with { it.method = "PUT"; it }
            .header("Authorization", bearer(secretary))
        mockMvc.perform(text).andExpect(status().isBadRequest)

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/societies/$liveSocietyId/logo")
                .header("Authorization", bearer(secretary))
        ).andExpect(status().isOk).andExpect(jsonPath("$.hasLogo").value(false))
    }
}
