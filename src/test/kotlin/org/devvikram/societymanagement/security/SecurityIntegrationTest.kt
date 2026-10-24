package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.society.application.SocietyService
import org.devvikram.societymanagement.user.application.AppUserService
import org.devvikram.societymanagement.user.application.CreateAppUserCommand
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.Role
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Runs against the real local Postgres (seeded permissions); @Transactional rolls back the users it creates.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var appUserService: AppUserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var cacheManager: CacheManager

    private lateinit var admin: AppUser
    private lateinit var resident: AppUser
    private lateinit var otherResident: AppUser
    private lateinit var guard: AppUser

    @BeforeEach
    fun setUp() {
        val societyId = societyService.createSociety("Security Test Society").id!!
        fun user(role: Role) = appUserService.createUser(
            CreateAppUserCommand(
                name = "$role user",
                phone = "9${(100000000..999999999).random()}",
                rawPassword = "secret123",
                role = role,
                societyId = societyId,
            )
        )
        admin = user(Role.ADMIN)
        resident = user(Role.RESIDENT)
        otherResident = user(Role.RESIDENT)
        guard = user(Role.GUARD)
    }

    private fun bearer(user: AppUser) = "Bearer ${jwtService.generateToken(user)}"

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
        appUserService.deactivate(resident.id!!)
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
        mockMvc.perform(get("/api/wings/${UUID.randomUUID()}").header("Authorization", bearer(guard)))
            .andExpect(status().isForbidden)
        // Admin passes the gate and reaches the service, which 404s on the unknown id.
        mockMvc.perform(get("/api/wings/${UUID.randomUUID()}").header("Authorization", bearer(admin)))
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
    fun `role without USER_PROFILE_UPDATE cannot update even own profile`() {
        mockMvc.perform(
            patch("/api/users/${guard.id}")
                .header("Authorization", bearer(guard))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Guard"}""")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `role permissions are cached and evictable`() {
        val cache = assertNotNull(cacheManager.getCache("rolePermissions"))
        permissionService.evictAll()
        assertNull(cache.get(Role.ADMIN))
        permissionService.getPermissionsForRole(Role.ADMIN)
        assertNotNull(cache.get(Role.ADMIN))
        permissionService.evictAll()
        assertNull(cache.get(Role.ADMIN))
    }
}
