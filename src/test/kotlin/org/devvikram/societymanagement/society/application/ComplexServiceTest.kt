package org.devvikram.societymanagement.society.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class ComplexServiceTest {

    private lateinit var complexRepository: InMemoryComplexRepository
    private lateinit var service: ComplexService

    @BeforeEach
    fun setUp() {
        complexRepository = InMemoryComplexRepository()
        service = ComplexService(complexRepository)
    }

    @Test
    fun `creates and retrieves a complex`() {
        val complex = service.createComplex("Green Valley Township", "Pune")

        val found = service.getById(complex.id!!)
        assertEquals("Green Valley Township", found.name)
    }

    @Test
    fun `updates settings`() {
        val complex = service.createComplex("Old Name")

        val updated = service.updateSettings(complex.id!!, name = "New Name", address = "New Address")

        assertEquals("New Name", updated.name)
        assertEquals("New Address", updated.address)
    }

    @Test
    fun `throws when the complex does not exist`() {
        assertThrows(NoSuchElementException::class.java) { service.getById(UUID.randomUUID()) }
    }
}
