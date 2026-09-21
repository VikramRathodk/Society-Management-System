package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Complex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ComplexRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var complexRepository: ComplexJpaRepository

    @Test
    fun `persists and reloads a complex`() {
        val complex = entityManager.persistFlushFind(Complex(name = "Green Valley Township", address = "Pune"))

        assertNotNull(complex.id)
        assertNotNull(complex.createdAt)

        val found = complexRepository.findById(complex.id!!).orElseThrow()
        assertEquals("Green Valley Township", found.name)
    }
}
