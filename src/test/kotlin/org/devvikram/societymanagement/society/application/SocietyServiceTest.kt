package org.devvikram.societymanagement.society.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class SocietyServiceTest {

    private lateinit var societyRepository: InMemorySocietyRepository
    private lateinit var complexRepository: InMemoryComplexRepository
    private lateinit var service: SocietyService

    @BeforeEach
    fun setUp() {
        societyRepository = InMemorySocietyRepository()
        complexRepository = InMemoryComplexRepository()
        service = SocietyService(societyRepository, complexRepository)
    }

    @Test
    fun `creates a standalone society with no complex`() {
        val society = service.createSociety("Sunrise Apartments", "Mumbai")

        assertNull(society.complex)
    }

    @Test
    fun `creates a society scoped to a complex`() {
        val complex = complexRepository.save(org.devvikram.societymanagement.society.domain.Complex(name = "Lakeview Township"))

        val society = service.createSociety("Lakeview Phase 1", complexId = complex.id)

        assertEquals(complex.id, society.complex?.id)
    }

    @Test
    fun `throws when the referenced complex does not exist`() {
        assertThrows(NoSuchElementException::class.java) {
            service.createSociety("Ghost", complexId = UUID.randomUUID())
        }
    }

    @Test
    fun `updates settings`() {
        val society = service.createSociety("Old Name")

        val updated = service.updateSettings(society.id!!, name = "New Name")

        assertEquals("New Name", updated.name)
    }
}
