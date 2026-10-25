package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.society.application.FlatService
import org.devvikram.societymanagement.society.application.SocietyService
import org.devvikram.societymanagement.society.application.WingService
import org.devvikram.societymanagement.user.application.UserService
import org.devvikram.societymanagement.user.application.AssignRoleCommand
import org.devvikram.societymanagement.user.application.CreateUserCommand
import org.devvikram.societymanagement.user.domain.User
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.cache.CacheManager
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Runs against the real local Postgres (seeded roles/permissions); @Transactional rolls back the users it creates.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var cacheManager: CacheManager

    private var societyId = 0L
    private var otherSocietyId = 0L
    private lateinit var admin: User
    private lateinit var secretary: User
    private lateinit var resident: User
    private lateinit var otherResident: User
    private lateinit var guard: User
    private lateinit var outsider: User

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
        societyId = societyService.createSociety("Security Test Society").id!!
        otherSocietyId = societyService.createSociety("Other Test Society").id!!
        val flatId = flatIn(societyId)
        admin = user("ADMIN")
        secretary = user("SECRETARY")
        resident = user("RESIDENT", flatId = flatId)
        otherResident = user("RESIDENT", flatId = flatId)
        guard = user("GUARD")
        outsider = user("GUARD", inSociety = otherSocietyId)
    }

    private fun flatIn(societyId: Long): Long {
        val wing = wingService.addWing(societyId, "A")
        return flatService.addFlat(wing.id!!, "101").id!!
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    @Test
    fun `missing token returns 401 with error body`() {
        mockMvc.perform(get("/api/users"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.path").value("/api/users"))
    }

    @Test
    fun `invalid token returns 401`() {
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer not-a-jwt"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `token of a deactivated user returns 401`() {
        val token = bearer(resident)
        userService.deactivate(resident.id!!)
        mockMvc.perform(get("/api/users/${resident.id}").header("Authorization", token))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `authenticated but missing permission returns 403`() {
        mockMvc.perform(get("/api/users").header("Authorization", bearer(resident)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `structure endpoints are permission-gated`() {
        mockMvc.perform(get("/api/wings/999999").header("Authorization", bearer(guard)))
            .andExpect(status().isForbidden)
        // Admin passes the gate and reaches the service, which 404s on the unknown id.
        mockMvc.perform(get("/api/wings/999999").header("Authorization", bearer(admin)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `resident can update own profile`() {
        mockMvc.perform(
            patch("/api/users/${resident.id}")
                .header("Authorization", bearer(resident))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Renamed"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Renamed"))
    }

    @Test
    fun `guard can update own profile to register an fcm token`() {
        mockMvc.perform(
            patch("/api/users/${guard.id}")
                .header("Authorization", bearer(guard))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fcmToken":"guard-device-token"}""")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `resident cannot update another user's profile`() {
        mockMvc.perform(
            patch("/api/users/${otherResident.id}")
                .header("Authorization", bearer(resident))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Hijacked"}""")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `admin can update another user's profile`() {
        mockMvc.perform(
            patch("/api/users/${resident.id}")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Fixed by admin"}""")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `user without any role cannot update even own profile`() {
        val roleless = user("GUARD")
        userService.getRoles(roleless.id!!).forEach { userService.removeRole(roleless.id!!, it.id!!) }

        mockMvc.perform(
            patch("/api/users/${roleless.id}")
                .header("Authorization", bearer(roleless))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Nobody"}""")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `secretary cannot deactivate a higher-ranked admin`() {
        mockMvc.perform(patch("/api/users/${admin.id}/deactivate").header("Authorization", bearer(secretary)))
            .andExpect(status().isForbidden)
        mockMvc.perform(patch("/api/users/${secretary.id}/deactivate").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
    }

    @Test
    fun `deactivated user's login says so, and logs in again once reactivated`() {
        val login = """{"phone": "${guard.phone}", "password": "secret123"}"""
        userService.deactivate(guard.id!!)

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.message").value("Account is deactivated"))

        mockMvc.perform(patch("/api/users/${guard.id}/activate").header("Authorization", bearer(resident)))
            .andExpect(status().isForbidden)
        mockMvc.perform(patch("/api/users/${guard.id}/activate").header("Authorization", bearer(secretary)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isActive").value(true))

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
            .andExpect(status().isOk)
    }

    @Test
    fun `admin cannot view a user outside their societies`() {
        mockMvc.perform(get("/api/users/${outsider.id}").header("Authorization", bearer(admin)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/users/${guard.id}").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
    }

    @Test
    fun `admin creates lower roles only in their own society`() {
        fun createGuard(inSociety: Long, roleCode: String = "GUARD") = mockMvc.perform(
            post("/api/users")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"New","phone":"${randomPhone()}","password":"secret123",""" +
                        """"role":"$roleCode","societyId":"$inSociety"}"""
                )
        )

        createGuard(societyId).andExpect(status().isCreated)
        createGuard(otherSocietyId).andExpect(status().isForbidden)
        createGuard(societyId, roleCode = "ADMIN").andExpect(status().isForbidden)
    }

    @Test
    fun `role lookups are cached and evictable`() {
        val permissions = assertNotNull(cacheManager.getCache("rolePermissions"))
        val subordinates = assertNotNull(cacheManager.getCache("roleSubordinates"))
        permissionService.evictAll()
        assertNull(permissions.get("ADMIN"))
        assertNull(subordinates.get("ADMIN"))

        permissionService.getPermissionsForRole("ADMIN")
        permissionService.getSubordinateRoles("ADMIN")
        assertNotNull(permissions.get("ADMIN"))
        assertNotNull(subordinates.get("ADMIN"))

        permissionService.evictAll()
        assertNull(permissions.get("ADMIN"))
        assertNull(subordinates.get("ADMIN"))
    }
}
