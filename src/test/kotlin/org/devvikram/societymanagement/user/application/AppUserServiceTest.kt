package org.devvikram.societymanagement.user.application

import org.devvikram.societymanagement.society.domain.Complex
import org.devvikram.societymanagement.society.domain.ComplexRepository
import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.FlatRepository
import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.society.domain.SocietyRepository
import org.devvikram.societymanagement.society.domain.Wing
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.AppUserRepository
import org.devvikram.societymanagement.user.domain.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.UUID

private class InMemoryAppUserRepository : AppUserRepository {
    private val store = mutableMapOf<UUID, AppUser>()

    override fun save(appUser: AppUser): AppUser {
        if (appUser.id == null) appUser.id = UUID.randomUUID()
        store[appUser.id!!] = appUser
        return appUser
    }

    override fun findById(id: UUID): AppUser? = store[id]
    override fun findAll(): List<AppUser> = store.values.toList()
    override fun findByPhone(phone: String): AppUser? = store.values.find { it.phone == phone }
}

private class InMemorySocietyRepository : SocietyRepository {
    private val store = mutableMapOf<UUID, Society>()

    override fun save(society: Society): Society {
        if (society.id == null) society.id = UUID.randomUUID()
        store[society.id!!] = society
        return society
    }

    override fun findById(id: UUID): Society? = store[id]
    override fun findAll(): List<Society> = store.values.toList()
}

private class InMemoryComplexRepository : ComplexRepository {
    private val store = mutableMapOf<UUID, Complex>()

    override fun save(complex: Complex): Complex {
        if (complex.id == null) complex.id = UUID.randomUUID()
        store[complex.id!!] = complex
        return complex
    }

    override fun findById(id: UUID): Complex? = store[id]
    override fun findAll(): List<Complex> = store.values.toList()
}

private class InMemoryFlatRepository : FlatRepository {
    private val store = mutableMapOf<UUID, Flat>()

    override fun save(flat: Flat): Flat {
        if (flat.id == null) flat.id = UUID.randomUUID()
        store[flat.id!!] = flat
        return flat
    }

    override fun findById(id: UUID): Flat? = store[id]
    override fun findAll(): List<Flat> = store.values.toList()
    override fun findByWingId(wingId: UUID): List<Flat> = store.values.filter { it.wing.id == wingId }
    override fun deleteById(id: UUID) {
        store.remove(id)
    }
}

class AppUserServiceTest {

    private lateinit var appUserRepository: InMemoryAppUserRepository
    private lateinit var societyRepository: InMemorySocietyRepository
    private lateinit var complexRepository: InMemoryComplexRepository
    private lateinit var flatRepository: InMemoryFlatRepository
    private lateinit var service: AppUserService
    private lateinit var society: Society

    @BeforeEach
    fun setUp() {
        appUserRepository = InMemoryAppUserRepository()
        societyRepository = InMemorySocietyRepository()
        complexRepository = InMemoryComplexRepository()
        flatRepository = InMemoryFlatRepository()
        service = AppUserService(
            appUserRepository,
            societyRepository,
            complexRepository,
            flatRepository,
            BCryptPasswordEncoder(),
        )
        society = societyRepository.save(Society(name = "Sunrise Apartments"))
    }

    @Test
    fun `creates a user and hashes the password`() {
        val user = service.createUser(
            CreateAppUserCommand(
                name = "Asha Patil",
                phone = "9000000001",
                rawPassword = "secret123",
                role = Role.RESIDENT,
                societyId = society.id,
            ),
        )

        assertNotEquals("secret123", user.passwordHash)
        assertTrue(BCryptPasswordEncoder().matches("secret123", user.passwordHash))
        assertEquals(society.id, user.society?.id)
    }

    @Test
    fun `resolves the flat when a flatId is provided`() {
        val wing = Wing(society = society, name = "A")
        val flat = flatRepository.save(Flat(wing = wing, flatNumber = "101"))

        val user = service.createUser(
            CreateAppUserCommand(
                name = "Asha Patil",
                phone = "9000000002",
                rawPassword = "secret123",
                role = Role.RESIDENT,
                societyId = society.id,
                flatId = flat.id,
            ),
        )

        assertEquals(flat.id, user.flat?.id)
    }

    @Test
    fun `rejects a user scoped to both society and complex`() {
        val complex = complexRepository.save(Complex(name = "Lakeview Township"))

        assertThrows(IllegalArgumentException::class.java) {
            service.createUser(
                CreateAppUserCommand(
                    name = "Bad Scope",
                    phone = "9000000003",
                    rawPassword = "secret123",
                    role = Role.ADMIN,
                    societyId = society.id,
                    complexId = complex.id,
                ),
            )
        }
    }

    @Test
    fun `rejects a user scoped to neither society nor complex`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.createUser(
                CreateAppUserCommand(
                    name = "No Scope",
                    phone = "9000000004",
                    rawPassword = "secret123",
                    role = Role.ADMIN,
                ),
            )
        }
    }

    @Test
    fun `rejects a duplicate phone number`() {
        service.createUser(
            CreateAppUserCommand(
                name = "First",
                phone = "9000000005",
                rawPassword = "secret123",
                role = Role.ADMIN,
                societyId = society.id,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.createUser(
                CreateAppUserCommand(
                    name = "Second",
                    phone = "9000000005",
                    rawPassword = "secret123",
                    role = Role.ADMIN,
                    societyId = society.id,
                ),
            )
        }
    }

    @Test
    fun `throws when the referenced society does not exist`() {
        assertThrows(NoSuchElementException::class.java) {
            service.createUser(
                CreateAppUserCommand(
                    name = "Ghost Society",
                    phone = "9000000006",
                    rawPassword = "secret123",
                    role = Role.ADMIN,
                    societyId = UUID.randomUUID(),
                ),
            )
        }
    }

    @Test
    fun `deactivates a user`() {
        val user = service.createUser(
            CreateAppUserCommand(
                name = "Guard",
                phone = "9000000007",
                rawPassword = "secret123",
                role = Role.GUARD,
                societyId = society.id,
            ),
        )

        val deactivated = service.deactivate(user.id!!)

        assertEquals(false, deactivated.isActive)
    }

    @Test
    fun `updates profile fields`() {
        val user = service.createUser(
            CreateAppUserCommand(
                name = "Old Name",
                phone = "9000000008",
                rawPassword = "secret123",
                role = Role.RESIDENT,
                societyId = society.id,
            ),
        )

        val updated = service.updateProfile(user.id!!, UpdateProfileCommand(name = "New Name", email = "new@example.com"))

        assertEquals("New Name", updated.name)
        assertEquals("new@example.com", updated.email)
    }
}
