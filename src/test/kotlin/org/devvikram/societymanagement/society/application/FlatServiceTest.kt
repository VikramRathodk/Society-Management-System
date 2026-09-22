package org.devvikram.societymanagement.society.application

import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.society.domain.Wing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class FlatServiceTest {

    private lateinit var flatRepository: InMemoryFlatRepository
    private lateinit var wingRepository: InMemoryWingRepository
    private lateinit var service: FlatService
    private lateinit var wing: Wing

    @BeforeEach
    fun setUp() {
        flatRepository = InMemoryFlatRepository()
        wingRepository = InMemoryWingRepository()
        service = FlatService(flatRepository, wingRepository)
        val society = Society(name = "Sunrise Apartments").also { it.id = UUID.randomUUID() }
        wing = wingRepository.save(Wing(society = society, name = "A"))
    }

    @Test
    fun `adds a flat to a wing`() {
        val flat = service.addFlat(wing.id!!, "101")

        assertEquals(wing.id, flat.wing.id)
    }

    @Test
    fun `throws when the wing does not exist`() {
        assertThrows(NoSuchElementException::class.java) { service.addFlat(UUID.randomUUID(), "101") }
    }

    @Test
    fun `rejects a duplicate flat number within the same wing`() {
        service.addFlat(wing.id!!, "101")

        assertThrows(IllegalArgumentException::class.java) { service.addFlat(wing.id!!, "101") }
    }

    @Test
    fun `lists flats scoped to a wing`() {
        service.addFlat(wing.id!!, "101")
        service.addFlat(wing.id!!, "102")

        assertEquals(2, service.listByWing(wing.id!!).size)
    }

    @Test
    fun `updates a flat number`() {
        val flat = service.addFlat(wing.id!!, "101")

        val updated = service.updateFlatNumber(flat.id!!, "101A")

        assertEquals("101A", updated.flatNumber)
    }

    @Test
    fun `rejects updating to a flat number already used in the wing`() {
        service.addFlat(wing.id!!, "101")
        val flat2 = service.addFlat(wing.id!!, "102")

        assertThrows(IllegalArgumentException::class.java) { service.updateFlatNumber(flat2.id!!, "101") }
    }

    @Test
    fun `deletes a flat`() {
        val flat = service.addFlat(wing.id!!, "101")

        service.deleteFlat(flat.id!!)

        assertThrows(NoSuchElementException::class.java) { service.getById(flat.id!!) }
    }
}
