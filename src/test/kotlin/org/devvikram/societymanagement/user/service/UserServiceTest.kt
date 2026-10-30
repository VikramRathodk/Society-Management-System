package org.devvikram.societymanagement.user.service

import org.devvikram.societymanagement.authorization.model.Role
import org.devvikram.societymanagement.authorization.model.RoleScope
import org.devvikram.societymanagement.authorization.repository.RoleRepository
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.model.Wing
import org.devvikram.societymanagement.society.service.InMemoryFlatRepository
import org.devvikram.societymanagement.society.service.InMemorySocietyRepository
import org.devvikram.societymanagement.user.model.ResidentType
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.model.UserRole
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

private class InMemoryUserRepository : UserRepository {
    private val store = mutableMapOf<Long, User>()
    private var nextId = 1L

    override fun save(user: User): User {
        if (user.id == null) user.id = nextId++
        store[user.id!!] = user
        return user
    }

    override fun findById(id: Long): User? = store[id]
    override fun findAll(): List<User> = store.values.toList()
    override fun findAllById(ids: Collection<Long>): List<User> = ids.mapNotNull { store[it] }
    override fun findByPhone(phone: String): User? = store.values.find { it.phone == phone }
    override fun clearFcmTokens(tokens: Collection<String>, exceptUserId: Long?): Int =
        store.values.filter { it.fcmToken in tokens && it.id != exceptUserId }.onEach { it.fcmToken = null }.size
    override fun delete(user: User) {
        store.remove(user.id)
    }
}

private class InMemoryUserRoleRepository : UserRoleRepository {
    private val store = mutableMapOf<Long, UserRole>()
    private var nextId = 1L

    override fun save(userRole: UserRole): UserRole {
        if (userRole.id == null) userRole.id = nextId++
        store[userRole.id!!] = userRole
        return userRole
    }

    override fun findById(id: Long): UserRole? = store[id]
    override fun findByUserId(userId: Long): List<UserRole> = store.values.filter { it.user.id == userId }
    override fun findByUserIds(userIds: Collection<Long>): List<UserRole> = store.values.filter { it.user.id in userIds }
    override fun findUserIdsBySocietyIds(societyIds: Collection<Long>): Set<Long> =
        store.values.filter { it.society?.id in societyIds }.map { it.user.id!! }.toSet()
    override fun findByFlatId(flatId: Long): List<UserRole> = store.values.filter { it.flat?.id == flatId }
    override fun delete(userRole: UserRole) {
        store.remove(userRole.id)
    }
}

private class InMemoryRoleRepository : RoleRepository {
    private val store = mutableMapOf<String, Role>()
    private var nextId = 1L

    override fun save(role: Role): Role {
        if (role.id == null) role.id = nextId++
        store[role.code] = role
        return role
    }

    override fun findByCode(code: String): Role? = store[code]
    override fun findByCodes(codes: Collection<String>): List<Role> = codes.mapNotNull { store[it] }
    override fun findAll(): List<Role> = store.values.toList()
    override fun delete(role: Role) {
        store.remove(role.code)
    }
}

class UserServiceTest {

    private lateinit var userRepository: InMemoryUserRepository
    private lateinit var userRoleRepository: InMemoryUserRoleRepository
    private lateinit var societyRepository: InMemorySocietyRepository
    private lateinit var flatRepository: InMemoryFlatRepository
    private lateinit var service: UserService
    private lateinit var society: Society
    private lateinit var flat: Flat

    @BeforeEach
    fun setUp() {
        userRepository = InMemoryUserRepository()
        userRoleRepository = InMemoryUserRoleRepository()
        societyRepository = InMemorySocietyRepository()
        flatRepository = InMemoryFlatRepository()
        val roleRepository = InMemoryRoleRepository().apply {
            save(Role(code = "SUPER_ADMIN", name = "Super Admin", scopeLevel = RoleScope.PLATFORM, allPermissions = true))
            save(Role(code = "ADMIN", name = "Admin", scopeLevel = RoleScope.SOCIETY))
            save(Role(code = "GUARD", name = "Security Guard", scopeLevel = RoleScope.SOCIETY))
            save(Role(code = "RESIDENT", name = "Resident", scopeLevel = RoleScope.SOCIETY, requiresFlat = true))
        }
        service = UserService(
            userRepository,
            userRoleRepository,
            roleRepository,
            societyRepository,
            flatRepository,
            BCryptPasswordEncoder(),
        )
        society = societyRepository.save(Society(name = "Sunrise Apartments"))
        flat = flatRepository.save(Flat(wing = Wing(society = society, name = "A"), flatNumber = "101"))
    }

    private fun create(phone: String, role: AssignRoleCommand, name: String = "User $phone") = service.createUser(
        CreateUserCommand(name = name, phone = phone, rawPassword = "secret123", role = role),
    )

    private fun guardIn(societyId: Long?) = AssignRoleCommand(roleCode = "GUARD", societyId = societyId)

    @Test
    fun `creates a user, hashes the password and assigns the first role`() {
        val user = create("9000000001", guardIn(society.id))

        assertNotEquals("secret123", user.passwordHash)
        assertTrue(BCryptPasswordEncoder().matches("secret123", user.passwordHash))
        val role = service.getRoles(user.id!!).single()
        assertEquals("GUARD", role.role.code)
        assertEquals(society.id, role.society?.id)
    }

    @Test
    fun `resident assignment resolves the flat and resident type`() {
        val user = create(
            "9000000002",
            AssignRoleCommand("RESIDENT", societyId = society.id, flatId = flat.id, residentType = ResidentType.OWNER),
        )

        val role = service.getRoles(user.id!!).single()
        assertEquals(flat.id, role.flat?.id)
        assertEquals(ResidentType.OWNER, role.residentType)
    }

    @Test
    fun `platform role is held without a society`() {
        val user = create("9000000003", AssignRoleCommand("SUPER_ADMIN"))

        assertNull(service.getRoles(user.id!!).single().society)
    }

    @Test
    fun `rejects a platform role scoped to a society`() {
        assertThrows(IllegalArgumentException::class.java) {
            create("9000000004", AssignRoleCommand("SUPER_ADMIN", societyId = society.id))
        }
    }

    @Test
    fun `rejects a society role without a society`() {
        assertThrows(IllegalArgumentException::class.java) {
            create("9000000005", guardIn(null))
        }
    }

    @Test
    fun `rejects a flat role without a flat`() {
        assertThrows(IllegalArgumentException::class.java) {
            create("9000000006", AssignRoleCommand("RESIDENT", societyId = society.id))
        }
    }

    @Test
    fun `rejects a flat on a role not tied to a flat`() {
        assertThrows(IllegalArgumentException::class.java) {
            create("9000000007", AssignRoleCommand("GUARD", societyId = society.id, flatId = flat.id))
        }
    }

    @Test
    fun `rejects a flat from another society`() {
        val other = societyRepository.save(Society(name = "Lakeview Towers"))

        assertThrows(IllegalArgumentException::class.java) {
            create("9000000008", AssignRoleCommand("RESIDENT", societyId = other.id, flatId = flat.id))
        }
    }

    @Test
    fun `throws when the role does not exist`() {
        assertThrows(NoSuchElementException::class.java) {
            create("9000000009", AssignRoleCommand("NO_SUCH_ROLE", societyId = society.id))
        }
    }

    @Test
    fun `throws when the referenced society does not exist`() {
        assertThrows(NoSuchElementException::class.java) {
            create("9000000010", guardIn(999_999L))
        }
    }

    @Test
    fun `rejects a duplicate phone number`() {
        create("9000000011", guardIn(society.id))

        assertThrows(IllegalArgumentException::class.java) {
            create("9000000011", guardIn(society.id))
        }
    }

    @Test
    fun `assigns the same role in a second society but not twice in one`() {
        val other = societyRepository.save(Society(name = "Lakeview Towers"))
        val user = create("9000000012", AssignRoleCommand("ADMIN", societyId = society.id))

        service.assignRole(user.id!!, AssignRoleCommand("ADMIN", societyId = other.id))
        assertEquals(2, service.getRoles(user.id!!).size)

        assertThrows(IllegalArgumentException::class.java) {
            service.assignRole(user.id!!, AssignRoleCommand("ADMIN", societyId = other.id))
        }
    }

    @Test
    fun `removes a role assignment only through its own user`() {
        val user = create("9000000013", guardIn(society.id))
        val other = create("9000000014", guardIn(society.id))
        val assignment = service.getRoles(user.id!!).single()

        assertThrows(NoSuchElementException::class.java) {
            service.removeRole(other.id!!, assignment.id!!)
        }

        service.removeRole(user.id!!, assignment.id!!)
        assertTrue(service.getRoles(user.id!!).isEmpty())
    }

    @Test
    fun `lists only users holding a role in the given societies`() {
        val other = societyRepository.save(Society(name = "Lakeview Towers"))
        val inside = create("9000000015", guardIn(society.id))
        create("9000000016", guardIn(other.id))
        create("9000000017", AssignRoleCommand("SUPER_ADMIN"))

        assertEquals(listOf(inside.id), service.getAllInSocieties(listOf(society.id!!)).map { it.id })
    }

    @Test
    fun `deactivates a user`() {
        val user = create("9000000018", guardIn(society.id))

        val deactivated = service.deactivate(user.id!!)

        assertEquals(false, deactivated.isActive)
    }

    @Test
    fun `reactivates a deactivated user`() {
        val user = create("9000000020", guardIn(society.id))
        service.deactivate(user.id!!)

        val activated = service.activate(user.id!!)

        assertEquals(true, activated.isActive)
    }

    @Test
    fun `updates profile fields`() {
        val user = create("9000000019", guardIn(society.id), name = "Old Name")

        val updated = service.updateProfile(user.id!!, UpdateProfileCommand(name = "New Name", email = "new@example.com"))

        assertEquals("New Name", updated.name)
        assertEquals("new@example.com", updated.email)
    }

    @Test
    fun `a blank push token clears it and a missing one leaves it alone`() {
        val user = create("9000000020", guardIn(society.id))
        service.updateProfile(user.id!!, UpdateProfileCommand(fcmToken = "token-a"))

        assertEquals("token-a", service.updateProfile(user.id!!, UpdateProfileCommand(name = "Renamed")).fcmToken)
        assertNull(service.updateProfile(user.id!!, UpdateProfileCommand(fcmToken = "  ")).fcmToken)
    }

    @Test
    fun `a push token moves to the account that registers it last`() {
        val first = create("9000000021", guardIn(society.id))
        val second = create("9000000022", guardIn(society.id))
        service.updateProfile(first.id!!, UpdateProfileCommand(fcmToken = "shared-phone"))

        service.updateProfile(second.id!!, UpdateProfileCommand(fcmToken = " shared-phone "))

        assertNull(service.getById(first.id!!).fcmToken)
        assertEquals("shared-phone", service.getById(second.id!!).fcmToken)
    }
}
