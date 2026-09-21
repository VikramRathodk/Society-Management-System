package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Complex
import org.devvikram.societymanagement.society.domain.Society
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
class SocietyRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var societyRepository: SocietyJpaRepository

    @Test
    fun `persists a standalone society with no complex`() {
        val society = entityManager.persistFlushFind(Society(name = "Sunrise Apartments", address = "Mumbai"))

        assertNotNull(society.id)
        assertNull(society.complex)

        val found = societyRepository.findById(society.id!!).orElseThrow()
        assertEquals("Sunrise Apartments", found.name)
    }

    @Test
    fun `persists a society scoped under a complex`() {
        val complex = entityManager.persistAndFlush(Complex(name = "Lakeview Township"))
        val society = entityManager.persistFlushFind(Society(name = "Lakeview Phase 1", complex = complex))

        assertEquals(complex.id, society.complex?.id)
    }
}
