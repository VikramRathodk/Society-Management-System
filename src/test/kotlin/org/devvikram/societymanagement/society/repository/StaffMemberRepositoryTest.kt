package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.model.StaffMember
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
class StaffMemberRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var staffMemberRepository: StaffMemberJpaRepository

    @Test
    fun `persists society-wide staff with no flat`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val staff = entityManager.persistFlushFind(
            StaffMember(society = society, name = "Ramesh", staffType = "sweeper"),
        )

        assertNotNull(staff.id)
        assertNull(staff.flat)
        assertEquals(true, staff.isActive)

        val found = staffMemberRepository.findById(staff.id!!).orElseThrow()
        assertEquals("Ramesh", found.name)
    }

    @Test
    fun `persists flat-specific staff`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val wing = entityManager.persistAndFlush(Wing(society = society, name = "A"))
        val flat = entityManager.persistAndFlush(Flat(wing = wing, flatNumber = "101"))
        val staff = entityManager.persistFlushFind(
            StaffMember(society = society, flat = flat, name = "Sunita", staffType = "maid"),
        )

        assertEquals(flat.id, staff.flat?.id)
    }
}
