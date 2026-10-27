package org.devvikram.societymanagement.user.repository

import org.devvikram.societymanagement.authorization.model.Role
import org.devvikram.societymanagement.authorization.repository.RoleJpaRepository
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.model.Wing
import org.devvikram.societymanagement.user.model.ResidentType
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.model.UserRole
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

// Roles come from the V3__dynamic_roles.sql seed data, so they are looked up, not persisted.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var userRepository: UserJpaRepository

    @Autowired
    private lateinit var userRoleRepository: UserRoleJpaRepository

    @Autowired
    private lateinit var roleRepository: RoleJpaRepository

    private fun role(code: String): Role = requireNotNull(roleRepository.findByCode(code)) { "Role $code not seeded" }

    private fun user(phone: String, name: String = "User $phone") =
        entityManager.persistAndFlush(User(name = name, phone = phone, passwordHash = "hashed"))

    @Test
    fun `persists a user with defaults`() {
        val saved = entityManager.persistFlushFind(
            User(name = "Asha Patil", phone = "8100000001", passwordHash = "hashed"),
        )

        assertNotNull(saved.id)
        assertNotNull(saved.createdAt)
        assertEquals(true, saved.isActive)
        assertEquals("Asha Patil", userRepository.findByPhone("8100000001")?.name)
    }

    @Test
    fun `rejects a duplicate phone number`() {
        user("8100000003")

        assertThrows(ConstraintViolationException::class.java) {
            user("8100000003")
        }
    }

    @Test
    fun `persists a resident role assignment scoped to a society and flat`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val wing = entityManager.persistAndFlush(Wing(society = society, name = "A"))
        val flat = entityManager.persistAndFlush(Flat(wing = wing, flatNumber = "101"))
        val resident = user("8100000004")

        entityManager.persistAndFlush(
            UserRole(
                user = resident,
                role = role("RESIDENT"),
                society = society,
                flat = flat,
                residentType = ResidentType.OWNER,
            ),
        )
        entityManager.clear()

        val found = userRoleRepository.findWithDetailsByUserIds(listOf(resident.id!!)).single()
        assertEquals("RESIDENT", found.role.code)
        assertEquals(society.id, found.society?.id)
        assertEquals(flat.id, found.flat?.id)
        assertEquals(ResidentType.OWNER, found.residentType)
        assertEquals(setOf(resident.id), userRoleRepository.findUserIdsBySocietyIds(listOf(society.id!!)))
    }

    @Test
    fun `persists a platform role assignment with no society or flat`() {
        val superAdmin = user("8100000005")

        val saved = entityManager.persistFlushFind(UserRole(user = superAdmin, role = role("SUPER_ADMIN")))

        assertNotNull(saved.id)
        assertNull(saved.society)
        assertNull(saved.flat)
    }

    @Test
    fun `rejects the same role twice in the same scope`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val guard = user("8100000006")
        entityManager.persistAndFlush(UserRole(user = guard, role = role("GUARD"), society = society))

        assertThrows(ConstraintViolationException::class.java) {
            entityManager.persistAndFlush(UserRole(user = guard, role = role("GUARD"), society = society))
        }
    }

    @Test
    fun `rejects the same platform role twice even though society is null`() {
        val superAdmin = user("8100000007")
        entityManager.persistAndFlush(UserRole(user = superAdmin, role = role("SUPER_ADMIN")))

        assertThrows(ConstraintViolationException::class.java) {
            entityManager.persistAndFlush(UserRole(user = superAdmin, role = role("SUPER_ADMIN")))
        }
    }

    @Test
    fun `allows the same role in different societies`() {
        val first = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val second = entityManager.persistAndFlush(Society(name = "Lakeview Towers"))
        val admin = user("8100000008")

        entityManager.persistAndFlush(UserRole(user = admin, role = role("ADMIN"), society = first))
        entityManager.persistAndFlush(UserRole(user = admin, role = role("ADMIN"), society = second))

        assertEquals(2, userRoleRepository.findWithDetailsByUserIds(listOf(admin.id!!)).size)
    }
}
