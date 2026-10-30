package org.devvikram.societymanagement.visitor.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.common.storage.ImageFormat
import org.devvikram.societymanagement.common.storage.Photo
import org.devvikram.societymanagement.common.storage.PhotoStorage
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.model.Wing
import org.devvikram.societymanagement.society.service.InMemoryFlatRepository
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.visitor.model.VisitorEntry
import org.devvikram.societymanagement.visitor.model.VisitorStatus
import org.devvikram.societymanagement.visitor.repository.VisitorEntryRepository
import org.devvikram.societymanagement.visitor.repository.VisitorSearch
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant

private class InMemoryUserRepository : UserRepository {
    private val store = mutableMapOf<Long, User>()
    private var nextId = 1L

    override fun save(user: User): User {
        if (user.id == null) user.id = nextId++
        store[user.id!!] = user
        return user
    }

    override fun findById(id: Long): User? = store[id]
    override fun findAll(): List<User> = store.values.toList()
    override fun findAllById(ids: Collection<Long>): List<User> = ids.mapNotNull { store[it] }
    override fun findByPhone(phone: String): User? = store.values.find { it.phone == phone }
    override fun clearFcmTokens(tokens: Collection<String>, exceptUserId: Long?): Int =
        store.values.filter { it.fcmToken in tokens && it.id != exceptUserId }.onEach { it.fcmToken = null }.size
    override fun delete(user: User) {
        store.remove(user.id)
    }
}

// Mirrors the conditional updates of VisitorEntryRepositoryImpl: a transition applies only from its expected state.
private class InMemoryVisitorEntryRepository : VisitorEntryRepository {
    private val store = mutableMapOf<Long, VisitorEntry>()
    private var nextId = 1L

    override fun save(entry: VisitorEntry): VisitorEntry {
        if (entry.id == null) entry.id = nextId++
        if (entry.loggedAt == null) entry.loggedAt = Instant.now()
        store[entry.id!!] = entry
        return entry
    }

    override fun findById(id: Long): VisitorEntry? = store[id]
    override fun search(search: VisitorSearch): List<VisitorEntry> = store.values.filter {
        it.society.id == search.societyId &&
            (search.flatIds == null || it.flat.id in search.flatIds) &&
            (search.status == null || it.status == search.status) &&
            (search.loggedByOrActive == null || it.loggedBy.id == search.loggedByOrActive || it.status in VisitorStatus.ACTIVE) &&
            (search.loggedFrom == null || !it.loggedAt!!.isBefore(search.loggedFrom)) &&
            (search.loggedTo == null || it.loggedAt!!.isBefore(search.loggedTo))
    }

    override fun respond(id: Long, decision: VisitorStatus, responder: User, asOverride: Boolean, at: Instant): Boolean =
        transition(id, VisitorStatus.PENDING, decision) {
            it.respondedBy = responder
            it.respondedAsOverride = asOverride
            it.respondedAt = at
        }

    override fun markEntered(id: Long, at: Instant): Boolean =
        transition(id, VisitorStatus.APPROVED, VisitorStatus.ENTERED) { it.enteredAt = at }

    override fun markExited(id: Long, at: Instant): Boolean =
        transition(id, VisitorStatus.ENTERED, VisitorStatus.EXITED) { it.exitedAt = at }

    override fun delete(entry: VisitorEntry) {
        store.remove(entry.id)
    }

    private fun transition(id: Long, from: VisitorStatus, to: VisitorStatus, apply: (VisitorEntry) -> Unit): Boolean {
        val entry = store[id]?.takeIf { it.status == from } ?: return false
        entry.status = to
        apply(entry)
        return true
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

class VisitorServiceTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private lateinit var visitors: InMemoryVisitorEntryRepository
    private lateinit var photos: InMemoryPhotoStorage
    private lateinit var events: MutableList<Any>
    private lateinit var service: VisitorService
    private lateinit var flat: Flat
    private lateinit var guard: User
    private lateinit var resident: User
    private lateinit var neighbour: User

    @BeforeEach
    fun setUp() {
        visitors = InMemoryVisitorEntryRepository()
        photos = InMemoryPhotoStorage()
        events = mutableListOf()
        val users = InMemoryUserRepository()
        val flats = InMemoryFlatRepository()
        service = VisitorService(visitors, flats, users, photos, ApplicationEventPublisher { events += it })

        val society = Society(name = "Sunrise Apartments").also { it.id = 1L }
        val wing = Wing(society = society, name = "A").also { it.id = 1L }
        flat = flats.save(Flat(wing = wing, flatNumber = "101"))
        guard = users.save(User(name = "Gate Guard", phone = "8200000001", passwordHash = "hashed"))
        resident = users.save(User(name = "Asha Patil", phone = "8200000002", passwordHash = "hashed"))
        neighbour = users.save(User(name = "Ravi Patil", phone = "8200000003", passwordHash = "hashed"))
    }

    private fun logVisitor(photo: ByteArray? = null): Long =
        service.logVisitor(LogVisitorCommand(flatId = flat.id!!, visitorName = "Courier", purpose = "Delivery", photo = photo), guard.id!!)

    @Test
    fun `logs a pending visitor in the flat's society and announces it`() {
        val entry = service.getById(logVisitor())

        assertEquals(VisitorStatus.PENDING, entry.status)
        assertEquals(1L, entry.society.id)
        assertEquals(guard.id, entry.loggedBy.id)
        assertNull(entry.photoKey)
        assertEquals(listOf(VisitorLogged(entry.id!!, flat.id!!, "101", "Courier", "Delivery")), events)
    }

    @Test
    fun `throws when the flat does not exist`() {
        assertThrows(NoSuchElementException::class.java) {
            service.logVisitor(LogVisitorCommand(flatId = 999L, visitorName = "Courier"), guard.id!!)
        }
    }

    @Test
    fun `stores the photo with its detected content type`() {
        val id = logVisitor(photo = jpeg)

        val photo = service.getPhoto(id)

        assertEquals("image/jpeg", photo.contentType)
        assertArrayEquals(jpeg, photo.data)
    }

    @Test
    fun `rejects a photo that is not a supported image`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            logVisitor(photo = "GIF89a not allowed".toByteArray())
        }
        assertTrue(error.message!!.contains("JPEG, PNG or WebP"))
        assertTrue(photos.store.isEmpty())
    }

    @Test
    fun `rejects a photo over the size limit`() {
        val tooBig = jpeg + ByteArray(ImageFormat.MAX_BYTES)

        assertThrows(IllegalArgumentException::class.java) { logVisitor(photo = tooBig) }
    }

    @Test
    fun `a resident's response is recorded and the guard is told`() {
        val id = logVisitor()
        events.clear()

        service.respond(id, VisitorStatus.APPROVED, resident.id!!)

        val entry = service.getById(id)
        assertEquals(VisitorStatus.APPROVED, entry.status)
        assertEquals(resident.id, entry.respondedBy?.id)
        assertEquals(false, entry.respondedAsOverride)
        assertEquals(
            listOf(VisitorResponded(id, guard.id!!, flat.id!!, "Courier", "101", VisitorStatus.APPROVED, "Asha Patil", false)),
            events,
        )
    }

    @Test
    fun `an override is recorded as one and announced as one`() {
        val id = logVisitor()
        events.clear()

        service.respond(id, VisitorStatus.REJECTED, guard.id!!, asOverride = true)

        val entry = service.getById(id)
        assertEquals(VisitorStatus.REJECTED, entry.status)
        assertEquals(true, entry.respondedAsOverride)
        assertEquals(true, (events.single() as VisitorResponded).asOverride)
    }

    @Test
    fun `a resident answering after an override is told it was one`() {
        val id = logVisitor()
        service.respond(id, VisitorStatus.APPROVED, guard.id!!, asOverride = true)

        val error = assertThrows(ConflictException::class.java) {
            service.respond(id, VisitorStatus.REJECTED, resident.id!!)
        }
        assertEquals("Already responded by Gate Guard (override)", error.message)
    }

    @Test
    fun `a second response conflicts and names who answered first`() {
        val id = logVisitor()
        service.respond(id, VisitorStatus.APPROVED, resident.id!!)

        val error = assertThrows(ConflictException::class.java) {
            service.respond(id, VisitorStatus.REJECTED, neighbour.id!!)
        }
        assertEquals("Already responded by Asha Patil", error.message)
        assertEquals(VisitorStatus.APPROVED, service.getById(id).status)
    }

    @Test
    fun `respond accepts only approve or reject`() {
        val id = logVisitor()

        assertThrows(IllegalArgumentException::class.java) { service.respond(id, VisitorStatus.ENTERED, resident.id!!) }
    }

    @Test
    fun `entering requires approval and exiting requires entry`() {
        val id = logVisitor()

        assertThrows(ConflictException::class.java) { service.markEntered(id) }
        service.respond(id, VisitorStatus.APPROVED, resident.id!!)
        assertThrows(ConflictException::class.java) { service.markExited(id) }

        service.markEntered(id)
        service.markExited(id)

        assertEquals(VisitorStatus.EXITED, service.getById(id).status)
    }

    @Test
    fun `deleting a visitor removes its photo`() {
        val id = logVisitor(photo = jpeg)

        service.delete(id)

        assertThrows(NoSuchElementException::class.java) { service.getById(id) }
        assertTrue(photos.store.isEmpty())
    }
}
