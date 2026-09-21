package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.society.domain.Wing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FlatRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var flatRepository: FlatJpaRepository

    @Test
    fun `persists a flat under a wing`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val wing = entityManager.persistAndFlush(Wing(society = society, name = "A"))
        val flat = entityManager.persistFlushFind(Flat(wing = wing, flatNumber = "101"))

        assertNotNull(flat.id)
        assertEquals(wing.id, flat.wing.id)

        val found = flatRepository.findById(flat.id!!).orElseThrow()
        assertEquals("101", found.flatNumber)
    }

    @Test
    fun `rejects a duplicate flat number within the same wing`() {
        val society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val wing = entityManager.persistAndFlush(Wing(society = society, name = "B"))
        entityManager.persistAndFlush(Flat(wing = wing, flatNumber = "201"))

        assertThrows(ConstraintViolationException::class.java) {
            entityManager.persistAndFlush(Flat(wing = wing, flatNumber = "201"))
        }
    }
}
