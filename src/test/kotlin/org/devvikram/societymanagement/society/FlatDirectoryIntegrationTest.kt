package org.devvikram.societymanagement.society

import org.devvikram.societymanagement.auth.security.JwtService
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

// Who sees which wings and flats. Real local Postgres with seeded roles, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FlatDirectoryIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService

    private var societyId = 0L
    private var wingA = 0L
    private var wingB = 0L
    private var flatA101 = 0L
    private var flatA102 = 0L
    private var flatB201 = 0L
    private lateinit var resident: User
    private lateinit var guard: User
    private lateinit var committee: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, flatId: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = societyId, flatId = flatId),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Directory Test Society").id!!
        wingA = wingService.addWing(societyId, "A").id!!
        wingB = wingService.addWing(societyId, "B").id!!
        flatA101 = flatService.addFlat(wingA, "101").id!!
        flatA102 = flatService.addFlat(wingA, "102").id!!
        flatB201 = flatService.addFlat(wingB, "201").id!!
        resident = user("RESIDENT", flatId = flatA101)
        guard = user("GUARD")
        committee = user("COMMITTEE")
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun getAs(user: User, path: String) = mockMvc.perform(get(path).header("Authorization", bearer(user)))

    @Test
    fun `a resident sees only their own wing and flat`() {
        getAs(resident, "/api/societies/$societyId/wings").andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(wingA))
        getAs(resident, "/api/wings/$wingA/flats").andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(flatA101))
        getAs(resident, "/api/wings/$wingB/flats").andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))

        getAs(resident, "/api/wings/$wingA").andExpect(status().isOk)
        getAs(resident, "/api/wings/$wingB").andExpect(status().isForbidden)
        getAs(resident, "/api/flats/$flatA101").andExpect(status().isOk)
        getAs(resident, "/api/flats/$flatA102").andExpect(status().isForbidden)
    }

    @Test
    fun `guards and the committee browse the whole directory`() {
        listOf(guard, committee).forEach {
            getAs(it, "/api/societies/$societyId/wings").andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(2))
            getAs(it, "/api/wings/$wingA/flats").andExpect(jsonPath("$.length()").value(2))
            getAs(it, "/api/wings/$wingB").andExpect(status().isOk)
            getAs(it, "/api/flats/$flatB201").andExpect(status().isOk)
        }
    }
}
