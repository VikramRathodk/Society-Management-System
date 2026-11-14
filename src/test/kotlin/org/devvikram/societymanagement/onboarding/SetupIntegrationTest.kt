package org.devvikram.societymanagement.onboarding

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.society.model.SocietyStatus
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
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
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

// Setup wizard steps 2 and 3 (step O3): wings and flats from a pattern, residents from a sheet, sent at
// go-live or straight away in a live society. Same annotations as the other integration tests.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class SetupIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var flatRepository: FlatRepository
    @Autowired lateinit var settings: SocietySettingsService

    private var societyId = 0L
    private lateinit var secretary: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, society: Long? = null, flat: Long? = null, type: ResidentType? = null, phone: String = randomPhone()) =
        userService.createUser(
            CreateUserCommand(
                name = "$roleCode user", phone = phone, rawPassword = "secret123",
                role = AssignRoleCommand(roleCode = roleCode, societyId = society, flatId = flat, residentType = type),
            )
        )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Setup Society", status = SocietyStatus.SETUP).id!!
        secretary = user("SECRETARY", society = societyId)
    }

    private fun send(method: String, path: String, by: User, body: String? = null): ResultActions {
        val builder = when (method) {
            "GET" -> get(path)
            "PATCH" -> patch(path)
            "DELETE" -> delete(path)
            else -> post(path)
        }.header("Authorization", "Bearer ${jwtService.generateToken(by)}")
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun upload(csv: String, by: User = secretary, params: Map<String, String> = emptyMap()): ResultActions {
        val builder = multipart("/api/societies/$societyId/resident-imports")
            .file(MockMultipartFile("file", "residents.csv", "text/csv", csv.toByteArray()))
            .header("Authorization", "Bearer ${jwtService.generateToken(by)}")
        params.forEach { (k, v) -> builder.param(k, v) }
        return mockMvc.perform(builder)
    }

    private fun json(result: ResultActions): String = result.andReturn().response.contentAsString

    private fun goLive(): ResultActions {
        send("PATCH", "/api/societies/$societyId", secretary,
            """{"address":"Plot 7","city":"Pune","state":"Maharashtra","pincode":"411001","contactPhone":"9000000001"}""")
            .andExpect(status().isOk)
        return send("POST", "/api/societies/$societyId/go-live", secretary).andExpect(status().isOk)
    }

    private fun flats(wing: String = "A", vararg numbers: String) {
        val wingId = wingService.addWing(societyId, wing).id!!
        numbers.forEach { flatService.addFlat(wingId, it) }
    }

    // ---- Wings & flats ----

    @Test
    fun `wings and flats from a pattern are previewed, generated, and generating again skips what exists`() {
        flats("A", "101")
        val body = """
            {"wings":[
              {"wing":"a","floorsFrom":1,"floorsTo":3,"flatsPerFloor":2},
              {"wing":"Shops","floorsFrom":0,"floorsTo":0,"flatsPerFloor":0,"extraUnits":["S1","S2"]}
            ]}
        """.trimIndent()
        send("POST", "/api/societies/$societyId/structure/preview", secretary, body)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("A"))
            .andExpect(jsonPath("$[0].exists").value(true))
            .andExpect(jsonPath("$[0].newFlats").value(5))
            .andExpect(jsonPath("$[0].existingFlats").value(1))
            .andExpect(jsonPath("$[0].flats[0].number").value("101"))
            .andExpect(jsonPath("$[0].flats[0].exists").value(true))
            .andExpect(jsonPath("$[1].exists").value(false))
            .andExpect(jsonPath("$[1].newFlats").value(2))
        assertEquals(1, flatRepository.findBySocietyId(societyId).size)

        send("POST", "/api/societies/$societyId/structure/generate", secretary, body)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].newFlats").value(5))
        assertEquals(2, wingService.listBySociety(societyId).size)
        assertEquals(8, flatRepository.findBySocietyId(societyId).size)

        send("POST", "/api/societies/$societyId/structure/generate", secretary, body)
            .andExpect(jsonPath("$[0].newFlats").value(0))
            .andExpect(jsonPath("$[1].existingFlats").value(2))
        assertEquals(8, flatRepository.findBySocietyId(societyId).size)

        send("GET", "/api/societies/$societyId/onboarding", secretary)
            .andExpect(jsonPath("$.structure.flats").value(8))
            .andExpect(jsonPath("$.structure.done").value(true))
    }

    @Test
    fun `a bad pattern or a caller without structure rights is refused`() {
        send("POST", "/api/societies/$societyId/structure/preview", secretary,
            """{"wings":[{"wing":"A","floorsFrom":1,"floorsTo":3,"flatsPerFloor":4,"numbering":"{floor}"}]}""")
            .andExpect(status().isBadRequest)
        send("POST", "/api/societies/$societyId/structure/preview", secretary, """{"wings":[]}""")
            .andExpect(status().isBadRequest)
        val guard = user("GUARD", society = societyId)
        send("POST", "/api/societies/$societyId/structure/generate", guard,
            """{"wings":[{"wing":"A","floorsFrom":1,"floorsTo":1,"flatsPerFloor":1}]}""")
            .andExpect(status().isForbidden)
    }

    // ---- Residents from a sheet ----

    @Test
    fun `a sheet is staged with each row checked, and sent when the society goes live`() {
        flats("A", "101", "102")
        flats("B", "101")
        val csv = """
            Wing,Flat No,Resident Name,Mobile,Owner/Tenant
            A,101,Meera Shah,+91 98111 00001,Owner
            A,101,Raj Shah,9811100002,family
            ,B-101,Kiran Rao,9811100003,Tenant
            A,999,Nobody,9811100004,Owner
            A,102,,9811100005,Owner
            A,102,Bad Phone,12345,Owner
            A,102,Odd Type,9811100006,caretaker
            A,101,Meera Again,9811100001,Owner
        """.trimIndent()
        val uploaded = upload(csv)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.columns.wing").value(1))
            .andExpect(jsonPath("$.columns.type").value(5))
            .andExpect(jsonPath("$.header[2]").value("Resident Name"))
            .andExpect(jsonPath("$.sheet.total").value(8))
            .andExpect(jsonPath("$.sheet.ready").value(3))
            .andExpect(jsonPath("$.sheet.withErrors").value(5))
            .andExpect(jsonPath("$.sheet.rows[0].phone").value("9811100001"))
            .andExpect(jsonPath("$.sheet.rows[0].flatLabel").value("A-101"))
            .andExpect(jsonPath("$.sheet.rows[2].flatLabel").value("B-101"))
            .andExpect(jsonPath("$.sheet.rows[2].residentType").value("TENANT"))
            .andExpect(jsonPath("$.sheet.rows[3].error").value("No flat A-999 in this society"))
            .andExpect(jsonPath("$.sheet.rows[4].error").value("Name is missing"))
            .andExpect(jsonPath("$.sheet.rows[5].error").value("12345 isn't a phone number"))
            .andExpect(jsonPath("$.sheet.rows[7].error").value("Same person and flat as row 2"))
        assert(JsonPath.read<String>(json(uploaded), "$.sheet.rows[6].error").contains("owner, tenant or family"))

        // Dropping a bad row.
        val badRow = JsonPath.read<Number>(json(uploaded), "$.sheet.rows[3].id").toLong()
        send("DELETE", "/api/societies/$societyId/resident-imports/current/rows/$badRow", secretary)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(7))

        send("GET", "/api/societies/$societyId/onboarding", secretary)
            .andExpect(jsonPath("$.residents.staged").value(3))
            .andExpect(jsonPath("$.residents.done").value(true))
        // Not while in Setup.
        send("POST", "/api/societies/$societyId/resident-imports/current/send", secretary)
            .andExpect(status().isConflict)

        val live = goLive()
            .andExpect(jsonPath("$.onboarding.status").value("LIVE"))
            .andExpect(jsonPath("$.residentInvitations.sent.length()").value(3))
            .andExpect(jsonPath("$.residentInvitations.skipped.length()").value(4))
            .andExpect(jsonPath("$.residentInvitations.sent[0].flatLabel").value("A-101"))
            .andExpect(jsonPath("$.residentInvitations.sent[0].name").value("Meera Shah"))
        val token = JsonPath.read<String>(json(live), "$.residentInvitations.sent[0].token")
        assert(JsonPath.read<String>(json(live), "$.residentInvitations.sent[0].inviteUrl").endsWith("/invite/$token"))

        // The link works: Meera joins A-101 as its owner.
        mockMvc.perform(post("/api/invitations/token/$token/accept").contentType(MediaType.APPLICATION_JSON).content("""{"password":"meera-pass"}"""))
            .andExpect(status().isOk)
        val meera = userService.getByPhone("9811100001")!!
        val role = userService.getRoles(meera.id!!).single()
        assertEquals("RESIDENT", role.role.code)
        assertEquals("101", role.flat?.flatNumber)
        assertEquals(ResidentType.OWNER, role.residentType)

        // The sent sheet is no longer current.
        send("GET", "/api/societies/$societyId/resident-imports/current", secretary).andExpect(status().isNoContent)
        val pending = json(send("GET", "/api/societies/$societyId/invitations?status=PENDING", secretary))
        assertEquals(2, JsonPath.read<List<Any>>(pending, "$[?(@.role == 'RESIDENT')]").size)
    }

    @Test
    fun `in a live society a sheet is sent straight away, keeping to the per-flat limit and skipping residents already there`() {
        societyId = societyService.createSociety("Live Society").id!!
        secretary = user("SECRETARY", society = societyId)
        flats("A", "101", "102")
        settings.update(societyId, mapOf(SocietySettingKeys.MAX_RESIDENTS_PER_FLAT to 2), secretary.id!!)
        val a101 = flatRepository.findBySocietyId(societyId).first { it.flatNumber == "101" }.id!!
        user("RESIDENT", society = societyId, flat = a101, type = ResidentType.OWNER, phone = "9822200001")

        upload("""
            Flat,Name,Phone
            A-101,Already Here,9822200001
            A-101,Second Person,9822200002
            A-101,Third Person,9822200003
            102,Only Number,9822200004
        """.trimIndent())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.sheet.rows[0].error").value("Already lives in A-101"))
            .andExpect(jsonPath("$.sheet.rows[1].error").doesNotExist())
            .andExpect(jsonPath("$.sheet.rows[2].error").value("A-101 is full (2 people allowed per flat)"))
            .andExpect(jsonPath("$.sheet.rows[3].flatLabel").value("A-102"))

        send("POST", "/api/societies/$societyId/resident-imports/current/send", secretary)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sent.length()").value(2))
            .andExpect(jsonPath("$.skipped.length()").value(2))
        send("POST", "/api/societies/$societyId/resident-imports/current/send", secretary)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `columns can be chosen, a new upload replaces the unsent sheet, and it can be discarded`() {
        flats("A", "101")
        upload("Col1,Col2,Col3\nx,y,z").andExpect(status().isBadRequest)
        upload("who,where,number\nAsha,101,9833300001", params = mapOf("nameColumn" to "1", "flatColumn" to "2", "phoneColumn" to "3"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.sheet.ready").value(1))
        upload("who,where,number\nAsha,101,9833300001", params = mapOf("nameColumn" to "1", "flatColumn" to "1", "phoneColumn" to "3"))
            .andExpect(status().isBadRequest)
        upload("Flat,Name,Phone\n101,Asha,9833300001\n101,Ben,9833300002")
            .andExpect(jsonPath("$.sheet.total").value(2))
        send("GET", "/api/societies/$societyId/resident-imports/current", secretary)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(2))
        send("DELETE", "/api/societies/$societyId/resident-imports/current", secretary).andExpect(status().isNoContent)
        send("GET", "/api/societies/$societyId/resident-imports/current", secretary).andExpect(status().isNoContent)

        val committee = user("COMMITTEE", society = societyId)
        upload("Flat,Name,Phone\n101,Asha,9833300001", by = committee).andExpect(status().isForbidden)
    }

    @Test
    fun `rows are checked again when read, as flats are added`() {
        upload("Flat,Name,Phone\nA-101,Asha,9844400001")
            .andExpect(jsonPath("$.sheet.rows[0].error").value("No flat A-101 in this society"))
        flats("A", "101")
        send("GET", "/api/societies/$societyId/resident-imports/current", secretary)
            .andExpect(jsonPath("$.rows[0].error").doesNotExist())
            .andExpect(jsonPath("$.ready").value(1))
    }
}
