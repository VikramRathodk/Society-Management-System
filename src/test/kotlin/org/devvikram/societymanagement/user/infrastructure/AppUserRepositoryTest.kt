package org.devvikram.societymanagement.user.infrastructure

import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.society.domain.Wing
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.ResidentType
import org.devvikram.societymanagement.user.domain.Role
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AppUserRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var appUserRepository: AppUserJpaRepository

    @Test
    fun `persists a resident scoped to a society and flat`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val wing = entityManager.persistAndFlush(Wing(society = society, name = "A"))
        val flat = entityManager.persistAndFlush(Flat(wing = wing, flatNumber = "101"))

        val resident = entityManager.persistFlushFind(
            AppUser(
                name = "Asha Patil",
                phone = "9000000001",
                passwordHash = "hashed",
                role = Role.RESIDENT,
                flat = flat,
                residentType = ResidentType.OWNER,
                society = society,
            ),
        )

        assertNotNull(resident.id)
        assertNotNull(resident.createdAt)
        assertEquals(true, resident.isActive)

        val found = appUserRepository.findByPhone("9000000001")
        assertNotNull(found)
        assertEquals(Role.RESIDENT, found?.role)
    }

    @Test
    fun `persists a guard scoped to a complex with no flat`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))

        val guard = entityManager.persistFlushFind(
            AppUser(
                name = "Security Guard",
                phone = "9000000002",
                passwordHash = "hashed",
                role = Role.GUARD,
                society = society,
            ),
        )

        assertNotNull(guard.id)
        assertNull(guard.flat)
        assertNull(guard.residentType)
    }

    @Test
    fun `rejects a duplicate phone number`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        entityManager.persistAndFlush(
            AppUser(name = "First User", phone = "9000000003", passwordHash = "hashed", role = Role.ADMIN, society = society),
        )

        assertThrows(ConstraintViolationException::class.java) {
            entityManager.persistAndFlush(
                AppUser(name = "Second User", phone = "9000000003", passwordHash = "hashed", role = Role.ADMIN, society = society),
            )
        }
    }
}
