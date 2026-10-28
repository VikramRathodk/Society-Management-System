package org.devvikram.societymanagement.communication.repository

import org.devvikram.societymanagement.communication.model.Notice
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.user.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
@Import(NoticeRepositoryImpl::class)
class NoticeRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: NoticeRepositoryImpl

    private lateinit var society: Society
    private lateinit var otherSociety: Society
    private lateinit var author: User

    @BeforeEach
    fun setUp() {
        society = entityManager.persistAndFlush(Society(name = "Sunrise Apartments"))
        otherSociety = entityManager.persistAndFlush(Society(name = "Moonlight Towers"))
        author = entityManager.persistAndFlush(User(name = "Meera Joshi", phone = "8300000101", passwordHash = "hashed"))
    }

    private fun post(title: String, inSociety: Society = society, at: Instant? = null): Notice =
        repository.save(Notice(society = inSociety, title = title, postedBy = author).also { it.createdAt = at })
            .also { entityManager.flush() }

    @Test
    fun `saves a notice with a timestamp and loads its associations`() {
        val id = post("Water cut on Sunday").id!!
        entityManager.clear()

        val found = repository.findById(id)!!

        assertEquals("Water cut on Sunday", found.title)
        assertEquals("Sunrise Apartments", found.society.name)
        assertEquals("Meera Joshi", found.postedBy.name)
        assertNotNull(found.createdAt)
        assertNull(found.updatedBy)
    }

    @Test
    fun `lists one society's notices newest first, a page at a time`() {
        val now = Instant.now()
        val oldest = post("Oldest", at = now.minusSeconds(120)).id!!
        val middle = post("Middle", at = now.minusSeconds(60)).id!!
        val newest = post("Newest", at = now).id!!
        post("Elsewhere", inSociety = otherSociety, at = now)
        entityManager.clear()

        assertEquals(listOf(newest, middle, oldest), repository.findBySocietyId(society.id!!, 0, 20).map { it.id })
        assertEquals(listOf(middle), repository.findBySocietyId(society.id!!, 1, 1).map { it.id })
    }

    @Test
    fun `delete removes the notice`() {
        val notice = post("Temporary")

        repository.delete(notice)
        entityManager.flush()
        entityManager.clear()

        assertNull(repository.findById(notice.id!!))
    }
}
