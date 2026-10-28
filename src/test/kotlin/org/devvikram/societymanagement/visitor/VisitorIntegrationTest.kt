package org.devvikram.societymanagement.visitor

import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.devvikram.societymanagement.visitor.model.VisitorStatus
import org.devvikram.societymanagement.visitor.repository.VisitorSearch
import org.devvikram.societymanagement.visitor.service.LogVisitorCommand
import org.devvikram.societymanagement.visitor.service.VisitorService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId

// Who may do what with visitors, end to end through the controller. Same setup as SecurityIntegrationTest:
// real local Postgres with seeded roles, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VisitorIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var visitorService: VisitorService

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private var societyId = 0L
    private var flatId = 0L
    private var otherFlatId = 0L
    private lateinit var guard: User
    private lateinit var resident: User
    private lateinit var flatmate: User
    private lateinit var neighbour: User
    private lateinit var admin: User
    private lateinit var secretary: User
    private lateinit var committee: User
    private lateinit var outsideGuard: User
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
        societyId = societyService.createSociety("Visitor Test Society").id!!
        val wing = wingService.addWing(societyId, "A")
        flatId = flatService.addFlat(wing.id!!, "101").id!!
        otherFlatId = flatService.addFlat(wing.id!!, "102").id!!
        guard = user("GUARD")
        resident = user("RESIDENT", flatId = flatId)
        flatmate = user("RESIDENT", flatId = flatId)
        neighbour = user("RESIDENT", flatId = otherFlatId)
        admin = user("ADMIN")
        secretary = user("SECRETARY")
        committee = user("COMMITTEE")
        val otherSocietyId = societyService.createSociety("Other Society").id!!
        outsideGuard = user("GUARD", inSociety = otherSocietyId)
        outsideSecretary = user("SECRETARY", inSociety = otherSocietyId)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun visitorFor(flat: Long): Long =
        visitorService.logVisitor(LogVisitorCommand(flatId = flat, visitorName = "Courier"), guard.id!!)

    @Test
    fun `guard logs a visitor with a photo that the flat's resident can view`() {
        val photo = MockMultipartFile("photo", "visitor.jpg", "image/jpeg", jpeg)
        mockMvc.perform(
            multipart("/api/visitors").file(photo)
                .param("flatId", flatId.toString())
                .param("visitorName", "Courier")
                .param("purpose", "Delivery")
                .header("Authorization", bearer(guard))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.flatNumber").value("101"))
            .andExpect(jsonPath("$.loggedById").value(guard.id!!))
            .andExpect(jsonPath("$.photoUrl").value(endsWith("/photo")))

        val id = visitorService.search(VisitorSearch(societyId)).single().id!!
        mockMvc.perform(get("/api/visitors/$id/photo").header("Authorization", bearer(resident)))
            .andExpect(status().isOk)
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(content().bytes(jpeg))
    }

    @Test
    fun `logging rejects missing fields and non-image photos`() {
        mockMvc.perform(
            multipart("/api/visitors").param("flatId", flatId.toString()).header("Authorization", bearer(guard))
        ).andExpect(status().isBadRequest)

        val notImage = MockMultipartFile("photo", "visitor.jpg", "image/jpeg", "plain text".toByteArray())
        mockMvc.perform(
            multipart("/api/visitors").file(notImage)
                .param("flatId", flatId.toString())
                .param("visitorName", "Courier")
                .header("Authorization", bearer(guard))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a guard of another society cannot log visitors here and residents cannot log at all`() {
        listOf(outsideGuard, resident).forEach {
            mockMvc.perform(
                multipart("/api/visitors")
                    .param("flatId", flatId.toString())
                    .param("visitorName", "Courier")
                    .header("Authorization", bearer(it))
            ).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `residents see only their own flat's visitors while the logging guard sees both`() {
        val own = visitorFor(flatId)
        val others = visitorFor(otherFlatId)

        mockMvc.perform(get("/api/visitors").param("societyId", societyId.toString()).header("Authorization", bearer(resident)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(own))
        mockMvc.perform(
            get("/api/visitors").param("societyId", societyId.toString()).param("flatId", otherFlatId.toString())
                .header("Authorization", bearer(resident))
        ).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/visitors/$others").header("Authorization", bearer(resident)))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/api/visitors").param("societyId", societyId.toString()).header("Authorization", bearer(guard)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
        mockMvc.perform(get("/api/visitors/$own").header("Authorization", bearer(outsideGuard)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `only a resident of the flat responds, and only once`() {
        val id = visitorFor(flatId)

        mockMvc.perform(post("/api/visitors/$id/approve").header("Authorization", bearer(neighbour)))
            .andExpect(status().isForbidden)
        // Committee holds the approval permission but not the override, and has no flat.
        mockMvc.perform(post("/api/visitors/$id/approve").header("Authorization", bearer(committee)))
            .andExpect(status().isForbidden)

        mockMvc.perform(post("/api/visitors/$id/approve").header("Authorization", bearer(resident)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.respondedById").value(resident.id!!))
            .andExpect(jsonPath("$.overridden").value(false))
        mockMvc.perform(post("/api/visitors/$id/reject").header("Authorization", bearer(flatmate)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("Already responded by ${resident.name}"))
    }

    @Test
    fun `secretary and admin may decide on the flat's behalf, recorded as an override`() {
        val bySecretary = visitorFor(flatId)
        val byAdmin = visitorFor(otherFlatId)

        listOf(guard, outsideSecretary).forEach {
            mockMvc.perform(post("/api/visitors/$bySecretary/approve").header("Authorization", bearer(it)))
                .andExpect(status().isForbidden)
        }

        mockMvc.perform(post("/api/visitors/$bySecretary/approve").header("Authorization", bearer(secretary)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.respondedById").value(secretary.id!!))
            .andExpect(jsonPath("$.overridden").value(true))
        mockMvc.perform(post("/api/visitors/$bySecretary/reject").header("Authorization", bearer(resident)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("Already responded by ${secretary.name} (override)"))

        mockMvc.perform(post("/api/visitors/$byAdmin/reject").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.overridden").value(true))
    }

    @Test
    fun `guard marks an approved visitor entered then exited`() {
        val id = visitorFor(flatId)

        mockMvc.perform(post("/api/visitors/$id/enter").header("Authorization", bearer(guard)))
            .andExpect(status().isConflict)

        visitorService.respond(id, VisitorStatus.APPROVED, resident.id!!)
        mockMvc.perform(post("/api/visitors/$id/enter").header("Authorization", bearer(resident)))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/api/visitors/$id/enter").header("Authorization", bearer(guard)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENTERED"))
        mockMvc.perform(post("/api/visitors/$id/exit").header("Authorization", bearer(guard)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("EXITED"))
    }

    @Test
    fun `deleting a visitor needs the delete permission`() {
        val id = visitorFor(flatId)

        mockMvc.perform(delete("/api/visitors/$id").header("Authorization", bearer(guard)))
            .andExpect(status().isForbidden)
        mockMvc.perform(delete("/api/visitors/$id").header("Authorization", bearer(admin)))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/visitors/$id").header("Authorization", bearer(admin)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `another guard sees active entries but not finished ones they didn't log, the committee sees all`() {
        val active = visitorFor(flatId)
        val finished = visitorFor(otherFlatId)
        visitorService.respond(finished, VisitorStatus.REJECTED, neighbour.id!!)
        val nextShift = user("GUARD")

        mockMvc.perform(get("/api/visitors").param("societyId", societyId.toString()).header("Authorization", bearer(nextShift)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(active))
        mockMvc.perform(get("/api/visitors/$active").header("Authorization", bearer(nextShift)))
            .andExpect(status().isOk)
        mockMvc.perform(get("/api/visitors/$finished").header("Authorization", bearer(nextShift)))
            .andExpect(status().isForbidden)

        // The guard who logged it still sees it once finished.
        mockMvc.perform(get("/api/visitors/$finished").header("Authorization", bearer(guard)))
            .andExpect(status().isOk)
        listOf(committee, secretary).forEach {
            mockMvc.perform(get("/api/visitors").param("societyId", societyId.toString()).header("Authorization", bearer(it)))
                .andExpect(jsonPath("$.length()").value(2))
        }
    }

    @Test
    fun `the committee exports the gate log as CSV, guards and residents cannot`() {
        visitorFor(flatId)
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()

        mockMvc.perform(
            get("/api/visitors/export").param("societyId", societyId.toString()).param("from", today).param("to", today)
                .header("Authorization", bearer(committee))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/csv;charset=UTF-8"))
            .andExpect(header().string("Content-Disposition", containsString("attachment")))
            .andExpect(content().string(startsWith("id,wing,flat,visitor,")))
            .andExpect(content().string(containsString(",A,101,Courier,")))

        listOf(guard, resident, outsideSecretary).forEach {
            mockMvc.perform(
                get("/api/visitors/export").param("societyId", societyId.toString()).param("from", today).param("to", today)
                    .header("Authorization", bearer(it))
            ).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `an export range must be ordered and at most a year`() {
        fun export(from: String, to: String) = mockMvc.perform(
            get("/api/visitors/export").param("societyId", societyId.toString()).param("from", from).param("to", to)
                .header("Authorization", bearer(committee))
        )
        export("2026-10-10", "2026-10-01").andExpect(status().isBadRequest)
        export("2025-01-01", "2026-10-01").andExpect(status().isBadRequest)
        export("not-a-date", "2026-10-01").andExpect(status().isBadRequest)
        export("2025-10-02", "2026-10-01").andExpect(status().isOk)
    }
}
