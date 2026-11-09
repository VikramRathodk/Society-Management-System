package org.devvikram.societymanagement.society

import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.society.service.FlatService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.assertEquals

// Same annotations and imported config as the maintenance integration tests, so they share one context.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class SocietySettingsIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var settingsService: SocietySettingsService

    private var societyId = 0L
    private lateinit var secretary: User
    private lateinit var committee: User
    private lateinit var resident: User
    private lateinit var otherSecretary: User

    private val tenantsKey = SocietySettingKeys.TENANTS_SEE_MAINTENANCE
    private val setting = "$.groups[0].settings[0]"

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, society: Long = societyId, flatId: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = society, flatId = flatId),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Settings Test Society").id!!
        val flatId = flatService.addFlat(wingService.addWing(societyId, "A").id!!, "101", BigDecimal("800")).id!!
        secretary = user("SECRETARY")
        committee = user("COMMITTEE")
        resident = user("RESIDENT", flatId = flatId)
        otherSecretary = user("SECRETARY", society = societyService.createSociety("Other Society").id!!)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun getSettings(user: User) =
        mockMvc.perform(get("/api/societies/$societyId/settings").header("Authorization", bearer(user)))

    private fun patchSettings(user: User, body: String): ResultActions = mockMvc.perform(
        patch("/api/societies/$societyId/settings").header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON).content(body)
    )

    @Test
    fun `a society starts with the defaults, grouped for the page`() {
        getSettings(secretary)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.societyId").value(societyId))
            .andExpect(jsonPath("$.groups[0].key").value("MAINTENANCE"))
            .andExpect(jsonPath("$.groups[0].label").value("Maintenance"))
            .andExpect(jsonPath("$setting.key").value(tenantsKey))
            .andExpect(jsonPath("$setting.type").value("BOOLEAN"))
            .andExpect(jsonPath("$setting.value").value(false))
            .andExpect(jsonPath("$setting.defaultValue").value(false))
            .andExpect(jsonPath("$setting.customized").value(false))
    }

    @Test
    fun `a secretary changes a setting and can put it back to the default`() {
        patchSettings(secretary, """{"$tenantsKey": true}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$setting.value").value(true))
            .andExpect(jsonPath("$setting.customized").value(true))
            .andExpect(jsonPath("$setting.updatedById").value(secretary.id!!))
        assertEquals(true, settingsService.boolean(societyId, tenantsKey))

        patchSettings(secretary, """{"$tenantsKey": null}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$setting.value").value(false))
            .andExpect(jsonPath("$setting.customized").value(false))
        assertEquals(false, settingsService.boolean(societyId, tenantsKey))
    }

    @Test
    fun `invalid changes are rejected whole`() {
        patchSettings(secretary, """{"$tenantsKey": "yes"}""").andExpect(status().isBadRequest)
        patchSettings(secretary, """{"$tenantsKey": true, "noSuchSetting": 1}""").andExpect(status().isBadRequest)
        patchSettings(secretary, "{}").andExpect(status().isBadRequest)
        assertEquals(false, settingsService.boolean(societyId, tenantsKey))
    }

    @Test
    fun `only the society's secretary and above see and change its settings`() {
        getSettings(resident).andExpect(status().isForbidden)
        getSettings(committee).andExpect(status().isForbidden)
        getSettings(otherSecretary).andExpect(status().isForbidden)
        patchSettings(committee, """{"$tenantsKey": true}""").andExpect(status().isForbidden)
        patchSettings(otherSecretary, """{"$tenantsKey": true}""").andExpect(status().isForbidden)
        assertEquals(false, settingsService.boolean(societyId, tenantsKey))
    }
}
