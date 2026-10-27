package org.devvikram.societymanagement.society

import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.society.service.ComplexService
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.devvikram.societymanagement.visitor.service.LogVisitorCommand
import org.devvikram.societymanagement.visitor.service.VisitorService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

// Deleting societies, complexes and users: super admin only, and refused while history references the
// record. Rolled back after each test. A refused delete aborts the Postgres transaction, so each 409
// check is the last database step of its test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeleteEndpointsIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var complexService: ComplexService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var visitorService: VisitorService

    private var societyId = 0L
    private var flatId = 0L
    private lateinit var superAdmin: User
    private lateinit var admin: User
    private lateinit var guard: User
    private lateinit var resident: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, inSociety: Long? = societyId, flatId: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = inSociety, flatId = flatId),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Delete Test Society").id!!
        flatId = flatService.addFlat(wingService.addWing(societyId, "A").id!!, "101").id!!
        superAdmin = user("SUPER_ADMIN", inSociety = null)
        admin = user("ADMIN")
        guard = user("GUARD")
        resident = user("RESIDENT", flatId = flatId)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    @Test
    fun `super admin deletes a society with no history, taking its flats and role assignments`() {
        mockMvc.perform(delete("/api/societies/$societyId").header("Authorization", bearer(admin)))
            .andExpect(status().isForbidden)

        mockMvc.perform(delete("/api/societies/$societyId").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/societies/$societyId").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/api/flats/$flatId").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isNotFound)
        assertEquals(emptyList<Any>(), userService.getRoles(resident.id!!))
    }

    @Test
    fun `a society with visitor history cannot be deleted`() {
        visitorService.logVisitor(LogVisitorCommand(flatId = flatId, visitorName = "Courier"), guard.id!!)

        mockMvc.perform(delete("/api/societies/$societyId").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value(
                "Society Delete Test Society still has visitors, notices, complaints, staff or parking slots and cannot be deleted"
            ))
    }

    @Test
    fun `super admin deletes an empty complex`() {
        val complexId = complexService.createComplex("Delete Test Complex").id!!

        mockMvc.perform(delete("/api/complexes/$complexId").header("Authorization", bearer(admin)))
            .andExpect(status().isForbidden)
        mockMvc.perform(delete("/api/complexes/$complexId").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/complexes/$complexId").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a complex with societies cannot be deleted`() {
        val complexId = complexService.createComplex("Delete Test Complex").id!!
        societyService.createSociety("Phase 1", complexId = complexId)

        mockMvc.perform(delete("/api/complexes/$complexId").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("Complex Delete Test Complex still has societies and cannot be deleted"))
    }

    @Test
    fun `only the super admin permanently deletes a user, and never themselves`() {
        // A society admin may deactivate the guard but not erase them.
        mockMvc.perform(delete("/api/users/${guard.id}").header("Authorization", bearer(admin)))
            .andExpect(status().isForbidden)
        mockMvc.perform(delete("/api/users/${superAdmin.id}").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isBadRequest)

        mockMvc.perform(delete("/api/users/${guard.id}").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/users/${guard.id}").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a user with visitor history cannot be deleted`() {
        visitorService.logVisitor(LogVisitorCommand(flatId = flatId, visitorName = "Courier"), guard.id!!)

        mockMvc.perform(delete("/api/users/${guard.id}").header("Authorization", bearer(superAdmin)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("${guard.name} has visitor, notice or complaint history; deactivate them instead"))
    }
}
