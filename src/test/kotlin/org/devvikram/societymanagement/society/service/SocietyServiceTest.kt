package org.devvikram.societymanagement.society.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.common.storage.Photo
import org.devvikram.societymanagement.common.storage.PhotoStorage
import org.devvikram.societymanagement.society.model.SocietyStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class SocietyServiceTest {

    private lateinit var societyRepository: InMemorySocietyRepository
    private lateinit var complexRepository: InMemoryComplexRepository
    private lateinit var photos: InMemoryPhotoStorage
    private lateinit var service: SocietyService

    @BeforeEach
    fun setUp() {
        societyRepository = InMemorySocietyRepository()
        complexRepository = InMemoryComplexRepository()
        photos = InMemoryPhotoStorage()
        service = SocietyService(societyRepository, complexRepository, photos)
    }

    @Test
    fun `creates a standalone society with no complex`() {
        val society = service.createSociety("Sunrise Apartments", "Mumbai")

        assertNull(society.complex)
        assertEquals(SocietyStatus.LIVE, society.status)
    }

    @Test
    fun `creates a society scoped to a complex`() {
        val complex = complexRepository.save(org.devvikram.societymanagement.society.model.Complex(name = "Lakeview Township"))

        val society = service.createSociety("Lakeview Phase 1", complexId = complex.id)

        assertEquals(complex.id, society.complex?.id)
    }

    @Test
    fun `throws when the referenced complex does not exist`() {
        assertThrows(NoSuchElementException::class.java) {
            service.createSociety("Ghost", complexId = 999_999L)
        }
    }

    @Test
    fun `updates the profile, trimming and clearing blank fields`() {
        val society = service.createSociety("Old Name", "Old street")

        val updated = service.updateProfile(
            society.id!!,
            SocietyProfileCommand(name = " New Name ", address = "", city = "Pune", notes = " Gate code with the guard "),
        )

        assertEquals("New Name", updated.name)
        assertNull(updated.address)
        assertEquals("Pune", updated.city)
        assertEquals("Gate code with the guard", updated.notes)
    }

    @Test
    fun `refuses a blank name`() {
        val society = service.createSociety("Sunrise")
        assertThrows(IllegalArgumentException::class.java) {
            service.updateProfile(society.id!!, SocietyProfileCommand(name = "  "))
        }
    }

    @Test
    fun `goes live only from setup and with a complete profile`() {
        val society = service.createSociety("Sunrise", status = SocietyStatus.SETUP)
        val error = assertThrows(IllegalArgumentException::class.java) { service.goLive(society.id!!, 7, Instant.EPOCH) }
        assertTrue(error.message!!.contains("city"))

        service.updateProfile(
            society.id!!,
            SocietyProfileCommand(address = "MG Road", city = "Pune", state = "Maharashtra", pincode = "411001", contactPhone = "9000000001"),
        )
        val live = service.goLive(society.id!!, 7, Instant.EPOCH)

        assertEquals(SocietyStatus.LIVE, live.status)
        assertEquals(7L, live.wentLiveBy)
        assertEquals(Instant.EPOCH, live.wentLiveAt)
        assertThrows(ConflictException::class.java) { service.goLive(society.id!!, 7, Instant.EPOCH) }
    }

    @Test
    fun `replaces and removes the logo, keeping only images`() {
        val society = service.createSociety("Sunrise")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2)

        service.setLogo(society.id!!, png)
        val first = society.logoKey
        service.setLogo(society.id!!, png)

        assertNotNull(service.logo(society.id!!))
        assertEquals(1, photos.store.size) // the old one is gone
        assertTrue(first != society.logoKey)
        assertThrows(IllegalArgumentException::class.java) { service.setLogo(society.id!!, "not an image".toByteArray()) }

        service.removeLogo(society.id!!)
        assertNull(service.logo(society.id!!))
        assertTrue(photos.store.isEmpty())
    }
}

private class InMemoryPhotoStorage : PhotoStorage {
    val store = mutableMapOf<String, Photo>()
    private var nextId = 1L

    override fun save(data: ByteArray, contentType: String): String =
        (nextId++).toString().also { store[it] = Photo(data, contentType) }

    override fun load(key: String): Photo? = store[key]

    override fun delete(key: String) {
        store.remove(key)
    }
}
