package org.devvikram.societymanagement.society.application

import org.devvikram.societymanagement.society.domain.Society
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class WingServiceTest {

    private lateinit var wingRepository: InMemoryWingRepository
    private lateinit var societyRepository: InMemorySocietyRepository
    private lateinit var service: WingService
    private lateinit var society: Society

    @BeforeEach
    fun setUp() {
        wingRepository = InMemoryWingRepository()
        societyRepository = InMemorySocietyRepository()
        service = WingService(wingRepository, societyRepository)
        society = societyRepository.save(Society(name = "Sunrise Apartments"))
    }

    @Test
    fun `adds a wing to a society`() {
        val wing = service.addWing(society.id!!, "A")

        assertEquals(society.id, wing.society.id)
    }

    @Test
    fun `throws when the society does not exist`() {
        assertThrows(NoSuchElementException::class.java) { service.addWing(UUID.randomUUID(), "A") }
    }

    @Test
    fun `lists wings scoped to a society`() {
        service.addWing(society.id!!, "A")
        service.addWing(society.id!!, "B")
        val otherSociety = societyRepository.save(Society(name = "Other Society"))
        service.addWing(otherSociety.id!!, "C")

        val wings = service.listBySociety(society.id!!)

        assertEquals(2, wings.size)
    }

    @Test
    fun `renames a wing`() {
        val wing = service.addWing(society.id!!, "A")

        val renamed = service.renameWing(wing.id!!, "A1")

        assertEquals("A1", renamed.name)
    }

    @Test
    fun `deletes a wing`() {
        val wing = service.addWing(society.id!!, "A")

        service.deleteWing(wing.id!!)

        assertThrows(NoSuchElementException::class.java) { service.getById(wing.id!!) }
    }
}
