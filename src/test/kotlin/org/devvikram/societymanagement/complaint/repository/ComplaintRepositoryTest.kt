package org.devvikram.societymanagement.complaint.repository

import org.devvikram.societymanagement.complaint.model.Complaint
import org.devvikram.societymanagement.complaint.model.ComplaintCategory
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.model.Wing
import org.devvikram.societymanagement.user.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
@Import(ComplaintRepositoryImpl::class)
class ComplaintRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: ComplaintRepositoryImpl

    private lateinit var society: Society
    private lateinit var flat: Flat
    private lateinit var otherFlat: Flat
    private lateinit var resident: User
    private lateinit var committee: User

    @BeforeEach
    fun setUp() {
        society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        val wing = entityManager.persistAndFlush(Wing(society = society, name = "A"))
        flat = entityManager.persistAndFlush(Flat(wing = wing, flatNumber = "101"))
        otherFlat = entityManager.persistAndFlush(Flat(wing = wing, flatNumber = "102"))
        resident = entityManager.persistAndFlush(User(name = "Asha Patil", phone = "8400000101", passwordHash = "hashed"))
        committee = entityManager.persistAndFlush(User(name = "Meera Joshi", phone = "8400000102", passwordHash = "hashed"))
    }

    private fun raise(
        onFlat: Flat = flat,
        category: ComplaintCategory = ComplaintCategory.PLUMBING,
        at: Instant? = null,
    ): Complaint =
        repository.save(
            Complaint(society = society, flat = onFlat, category = category, description = "Leak", raisedBy = resident)
                .also { it.createdAt = at }
        ).also { entityManager.flush() }

    @Test
    fun `saves an open complaint with a timestamp and loads its associations`() {
        val id = raise().id!!
        entityManager.clear()

        val found = repository.findById(id)!!

        assertEquals(ComplaintStatus.OPEN, found.status)
        assertEquals("101", found.flat.flatNumber)
        assertEquals("Sunrise Apartments", found.society.name)
        assertEquals("Asha Patil", found.raisedBy.name)
        assertNotNull(found.createdAt)
        assertNull(found.updatedBy)
    }

    @Test
    fun `searches newest first, narrowed by flat, status and category`() {
        val now = Instant.now()
        val older = raise(at = now.minusSeconds(60)).id!!
        val newer = raise(category = ComplaintCategory.ELECTRICAL, at = now).id!!
        val neighbours = raise(onFlat = otherFlat, at = now.minusSeconds(30)).id!!
        repository.changeStatus(older, setOf(ComplaintStatus.OPEN), ComplaintStatus.IN_PROGRESS, committee, now)

        assertEquals(listOf(newer, neighbours, older), repository.search(ComplaintSearch(society.id!!)).map { it.id })
        assertEquals(listOf(newer, older), repository.search(ComplaintSearch(society.id!!, flatIds = setOf(flat.id!!))).map { it.id })
        assertEquals(listOf(older), repository.search(ComplaintSearch(society.id!!, status = ComplaintStatus.IN_PROGRESS)).map { it.id })
        assertEquals(listOf(newer), repository.search(ComplaintSearch(society.id!!, category = ComplaintCategory.ELECTRICAL)).map { it.id })
        assertEquals(emptyList<Long>(), repository.search(ComplaintSearch(society.id!!, flatIds = emptySet())).map { it.id })
        assertEquals(listOf(neighbours), repository.search(ComplaintSearch(society.id!!, page = 1, size = 1)).map { it.id })
    }

    @Test
    fun `a status change applies only from an expected state and records who made it`() {
        val id = raise().id!!

        assertFalse(repository.changeStatus(id, setOf(ComplaintStatus.RESOLVED), ComplaintStatus.CLOSED, committee, Instant.now()))
        assertTrue(repository.changeStatus(id, setOf(ComplaintStatus.OPEN), ComplaintStatus.RESOLVED, committee, Instant.now()))

        val found = repository.findById(id)!!
        assertEquals(ComplaintStatus.RESOLVED, found.status)
        assertEquals("Meera Joshi", found.updatedBy?.name)
        assertNotNull(found.updatedAt)
    }
}
