package org.devvikram.societymanagement.notice.service

import org.devvikram.societymanagement.notice.model.Notice
import org.devvikram.societymanagement.notice.repository.NoticeRepository
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.service.InMemorySocietyRepository
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

private class InMemoryNoticeRepository : NoticeRepository {
    val store = mutableMapOf<Long, Notice>()
    private var nextId = 1L

    override fun save(notice: Notice): Notice {
        if (notice.id == null) notice.id = nextId++
        if (notice.createdAt == null) notice.createdAt = Instant.now()
        store[notice.id!!] = notice
        return notice
    }

    override fun findById(id: Long): Notice? = store[id]
    override fun findBySocietyId(societyId: Long, page: Int, size: Int): List<Notice> =
        store.values.filter { it.society.id == societyId }
            .sortedWith(compareByDescending<Notice> { it.createdAt }.thenByDescending { it.id })
            .drop(page * size).take(size)

    override fun delete(notice: Notice) {
        store.remove(notice.id)
    }
}

class NoticeServiceTest {

    private lateinit var notices: InMemoryNoticeRepository
    private lateinit var events: MutableList<Any>
    private lateinit var service: NoticeService
    private lateinit var society: Society
    private lateinit var committee: User
    private lateinit var secretary: User

    @BeforeEach
    fun setUp() {
        notices = InMemoryNoticeRepository()
        events = mutableListOf()
        val societies = InMemorySocietyRepository()
        val users = InMemoryUserRepository()
        service = NoticeService(notices, societies, users, ApplicationEventPublisher { events += it })

        society = societies.save(Society(name = "Sunrise Apartments"))
        committee = users.save(User(name = "Meera Joshi", phone = "8300000001", passwordHash = "hashed"))
        secretary = users.save(User(name = "Anil Kulkarni", phone = "8300000002", passwordHash = "hashed"))
    }

    private fun post(title: String = "Water cut on Sunday", description: String? = null) =
        service.post(PostNoticeCommand(society.id!!, title, description), committee.id!!)

    @Test
    fun `posting records the society and author and announces the notice`() {
        val id = post(description = "  10am to 2pm  ")

        val notice = service.getById(id)
        assertEquals(society.id, notice.society.id)
        assertEquals(committee.id, notice.postedBy.id)
        assertEquals("10am to 2pm", notice.description)
        assertNotNull(notice.createdAt)
        assertNull(notice.updatedAt)
        assertEquals(listOf(NoticePosted(id, society.id!!, committee.id!!, "Water cut on Sunday")), events)
    }

    @Test
    fun `posting rejects a blank title and an unknown society`() {
        assertThrows(IllegalArgumentException::class.java) { post(title = "   ") }
        assertThrows(NoSuchElementException::class.java) {
            service.post(PostNoticeCommand(999L, "Title"), committee.id!!)
        }
        assertTrue(notices.store.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an update changes only the given fields and records who edited`() {
        val id = post(description = "Original details")

        service.update(id, UpdateNoticeCommand(title = "Water cut moved to Monday"), secretary.id!!)

        val notice = service.getById(id)
        assertEquals("Water cut moved to Monday", notice.title)
        assertEquals("Original details", notice.description)
        assertEquals(committee.id, notice.postedBy.id)
        assertEquals(secretary.id, notice.updatedBy?.id)
        assertNotNull(notice.updatedAt)
    }

    @Test
    fun `an empty description clears it and a blank title is refused`() {
        val id = post(description = "Original details")

        service.update(id, UpdateNoticeCommand(description = ""), committee.id!!)
        assertNull(service.getById(id).description)

        assertThrows(IllegalArgumentException::class.java) {
            service.update(id, UpdateNoticeCommand(title = " "), committee.id!!)
        }
    }

    @Test
    fun `deleting removes the notice and unknown ids are not found`() {
        val id = post()

        service.delete(id)

        assertThrows(NoSuchElementException::class.java) { service.getById(id) }
        assertThrows(NoSuchElementException::class.java) { service.delete(id) }
    }

    @Test
    fun `the board lists only this society's notices, newest first`() {
        val older = post(title = "Older")
        notices.store[older]!!.createdAt = Instant.now().minusSeconds(60)
        val newer = post(title = "Newer")

        assertEquals(listOf(newer, older), service.listBySociety(society.id!!, 0, 20).map { it.id })
        assertEquals(listOf(older), service.listBySociety(society.id!!, 1, 1).map { it.id })
        assertTrue(service.listBySociety(999L, 0, 20).isEmpty())
    }
}
