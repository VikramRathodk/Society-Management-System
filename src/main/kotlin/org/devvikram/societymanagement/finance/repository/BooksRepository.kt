package org.devvikram.societymanagement.finance.repository

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.devvikram.societymanagement.finance.model.FinanceBooks
import org.devvikram.societymanagement.society.model.Society
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface BooksRepository {
    fun save(books: FinanceBooks): FinanceBooks
    fun findBySocietyId(societyId: Long): FinanceBooks?
    // Row lock on the society, the same one maintenance takes for money writes, so voucher numbers and
    // book changes never interleave with each other or with maintenance postings.
    fun lockSociety(societyId: Long)
}

interface BooksJpaRepository : JpaRepository<FinanceBooks, Long>

@Repository
class BooksRepositoryImpl(
    private val booksJpaRepository: BooksJpaRepository,
    private val entityManager: EntityManager,
) : BooksRepository {
    override fun save(books: FinanceBooks): FinanceBooks = booksJpaRepository.save(books)

    override fun findBySocietyId(societyId: Long): FinanceBooks? = booksJpaRepository.findById(societyId).orElse(null)

    override fun lockSociety(societyId: Long) {
        entityManager.find(Society::class.java, societyId, LockModeType.PESSIMISTIC_WRITE)
            ?: throw NoSuchElementException("Society $societyId not found")
    }
}
