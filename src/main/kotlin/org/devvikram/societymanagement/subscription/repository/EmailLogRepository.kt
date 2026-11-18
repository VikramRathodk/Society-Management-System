package org.devvikram.societymanagement.subscription.repository

import org.devvikram.societymanagement.subscription.model.EmailLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface EmailLogRepository {
    fun save(log: EmailLog): EmailLog
    // Newest first.
    fun findBySociety(societyId: Long): List<EmailLog>
}

interface EmailLogJpaRepository : JpaRepository<EmailLog, Long> {
    fun findBySocietyIdOrderByCreatedAtDescIdDesc(societyId: Long): List<EmailLog>
}

@Repository
class EmailLogRepositoryImpl(private val jpa: EmailLogJpaRepository) : EmailLogRepository {
    override fun save(log: EmailLog): EmailLog = jpa.saveAndFlush(log)
    override fun findBySociety(societyId: Long): List<EmailLog> = jpa.findBySocietyIdOrderByCreatedAtDescIdDesc(societyId)
}
