package org.devvikram.societymanagement.visitor.infrastructure

import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.society.domain.Wing
import org.devvikram.societymanagement.user.domain.User
import org.devvikram.societymanagement.visitor.domain.VisitorEntry
import org.devvikram.societymanagement.visitor.domain.VisitorSearch
import org.devvikram.societymanagement.visitor.domain.VisitorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(VisitorEntryRepositoryImpl::class)
class VisitorEntryRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: VisitorEntryRepositoryImpl

    private lateinit var society: Society
    private lateinit var flat101: Flat
    private lateinit var flat102: Flat
    private lateinit var guard: User
    private lateinit var resident: User

    @BeforeEach
    fun setUp() {
        society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val wing = entityManager.persistAndFlush(Wing(society = society, name = "A"))
        flat101 = entityManager.persistAndFlush(Flat(wing = wing, flatNumber = "101"))
        flat102 = entityManager.persistAndFlush(Flat(wing = wing, flatNumber = "102"))
        guard = entityManager.persistAndFlush(User(name = "Gate Guard", phone = "8200000001", passwordHash = "hashed"))
        resident = entityManager.persistAndFlush(User(name = "Asha Patil", phone = "8200000002", passwordHash = "hashed"))
    }

    private fun log(flat: Flat, name: String = "Courier"): VisitorEntry =
        repository.save(VisitorEntry(society = society, flat = flat, visitorName = name, loggedBy = guard))
            .also { entityManager.flush() }

    @Test
    fun `logs a pending entry with a timestamp and loads its associations`() {
        val id = log(flat101).id!!
        entityManager.clear()

        val found = repository.findById(id)!!

        assertEquals(VisitorStatus.PENDING, found.status)
        assertNotNull(found.loggedAt)
        assertEquals("101", found.flat.flatNumber)
        assertEquals("Gate Guard", found.loggedBy.name)
    }

    @Test
    fun `only the first response to a pending entry applies`() {
        val id = log(flat101).id!!
        val at = Instant.now()

        assertTrue(repository.respond(id, VisitorStatus.APPROVED, resident, false, at))
        assertFalse(repository.respond(id, VisitorStatus.REJECTED, guard, false, at))

        val found = repository.findById(id)!!
        assertEquals(VisitorStatus.APPROVED, found.status)
        assertEquals(resident.id, found.respondedBy?.id)
        assertNotNull(found.respondedAt)
    }

    @Test
    fun `records whether the response was an override`() {
        val byResident = log(flat101).id!!
        val byOverride = log(flat102).id!!

        repository.respond(byResident, VisitorStatus.APPROVED, resident, false, Instant.now())
        repository.respond(byOverride, VisitorStatus.REJECTED, guard, true, Instant.now())

        assertFalse(repository.findById(byResident)!!.respondedAsOverride)
        assertTrue(repository.findById(byOverride)!!.respondedAsOverride)
    }

    @Test
    fun `gate actions follow approved, entered, exited in order`() {
        val id = log(flat101).id!!
        val at = Instant.now()

        assertFalse(repository.markEntered(id, at), "pending visitor must not enter")
        repository.respond(id, VisitorStatus.APPROVED, resident, false, at)
        assertFalse(repository.markExited(id, at), "visitor must enter before exiting")
        assertTrue(repository.markEntered(id, at))
        assertTrue(repository.markExited(id, at))
        assertFalse(repository.markExited(id, at), "exit applies once")

        val found = repository.findById(id)!!
        assertEquals(VisitorStatus.EXITED, found.status)
        assertNotNull(found.enteredAt)
        assertNotNull(found.exitedAt)
    }

    @Test
    fun `a rejected visitor cannot enter`() {
        val id = log(flat101).id!!
        repository.respond(id, VisitorStatus.REJECTED, resident, false, Instant.now())

        assertFalse(repository.markEntered(id, Instant.now()))
    }

    @Test
    fun `search filters by flat and status, newest first`() {
        val first = log(flat101, "First")
        val second = log(flat102, "Second")
        val third = log(flat101, "Third")
        repository.respond(third.id!!, VisitorStatus.APPROVED, resident, false, Instant.now())

        val all = repository.search(VisitorSearch(society.id!!))
        assertEquals(listOf(third.id, second.id, first.id), all.map { it.id })

        val ownFlat = repository.search(VisitorSearch(society.id!!, flatIds = setOf(flat101.id!!)))
        assertEquals(listOf(third.id, first.id), ownFlat.map { it.id })

        val pending = repository.search(VisitorSearch(society.id!!, status = VisitorStatus.PENDING))
        assertEquals(listOf(second.id, first.id), pending.map { it.id })

        assertEquals(emptyList<VisitorEntry>(), repository.search(VisitorSearch(society.id!!, flatIds = emptySet())))
    }

    @Test
    fun `search pages through results`() {
        repeat(3) { log(flat101, "Visitor $it") }

        val page0 = repository.search(VisitorSearch(society.id!!, page = 0, size = 2))
        val page1 = repository.search(VisitorSearch(society.id!!, page = 1, size = 2))

        assertEquals(2, page0.size)
        assertEquals(1, page1.size)
        assertEquals("Visitor 0", page1.single().visitorName)
    }
}
