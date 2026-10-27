package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.ParkingSlot
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.model.Wing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ParkingSlotRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var parkingSlotRepository: ParkingSlotJpaRepository

    @Test
    fun `persists an unassigned parking slot`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val slot = entityManager.persistFlushFind(ParkingSlot(society = society, slotNumber = "P-01", vehicleType = "car"))

        assertNotNull(slot.id)
        assertNull(slot.flat)

        val found = parkingSlotRepository.findById(slot.id!!).orElseThrow()
        assertEquals("P-01", found.slotNumber)
    }

    @Test
    fun `persists a parking slot assigned to a flat`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val wing = entityManager.persistAndFlush(Wing(society = society, name = "A"))
        val flat = entityManager.persistAndFlush(Flat(wing = wing, flatNumber = "101"))
        val slot = entityManager.persistFlushFind(ParkingSlot(society = society, slotNumber = "P-02", flat = flat))

        assertEquals(flat.id, slot.flat?.id)
    }
}
