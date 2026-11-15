package org.devvikram.societymanagement.policy.repository

import org.devvikram.societymanagement.policy.model.PolicyAcknowledgement
import org.devvikram.societymanagement.policy.model.PolicyReminder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface PolicyAcknowledgementRepository {
    fun save(acknowledgement: PolicyAcknowledgement): PolicyAcknowledgement
    fun find(versionId: Long, userId: Long): PolicyAcknowledgement?
    fun findByVersion(versionId: Long): List<PolicyAcknowledgement>
    fun findByUser(userId: Long, versionIds: Collection<Long>): List<PolicyAcknowledgement>
}

interface PolicyAcknowledgementJpaRepository : JpaRepository<PolicyAcknowledgement, Long> {
    fun findByVersionIdAndUserId(versionId: Long, userId: Long): PolicyAcknowledgement?
    fun findByVersionId(versionId: Long): List<PolicyAcknowledgement>
    fun findByUserIdAndVersionIdIn(userId: Long, versionIds: Collection<Long>): List<PolicyAcknowledgement>
}

@Repository
class PolicyAcknowledgementRepositoryImpl(private val jpa: PolicyAcknowledgementJpaRepository) : PolicyAcknowledgementRepository {
    override fun save(acknowledgement: PolicyAcknowledgement): PolicyAcknowledgement = jpa.saveAndFlush(acknowledgement)

    override fun find(versionId: Long, userId: Long): PolicyAcknowledgement? = jpa.findByVersionIdAndUserId(versionId, userId)

    override fun findByVersion(versionId: Long): List<PolicyAcknowledgement> = jpa.findByVersionId(versionId)

    override fun findByUser(userId: Long, versionIds: Collection<Long>): List<PolicyAcknowledgement> =
        if (versionIds.isEmpty()) emptyList() else jpa.findByUserIdAndVersionIdIn(userId, versionIds)
}

interface PolicyReminderRepository {
    fun save(reminder: PolicyReminder): PolicyReminder
    fun findByVersion(versionId: Long): List<PolicyReminder>
}

interface PolicyReminderJpaRepository : JpaRepository<PolicyReminder, Long> {
    fun findByVersionId(versionId: Long): List<PolicyReminder>
}

@Repository
class PolicyReminderRepositoryImpl(private val jpa: PolicyReminderJpaRepository) : PolicyReminderRepository {
    override fun save(reminder: PolicyReminder): PolicyReminder = jpa.save(reminder)

    override fun findByVersion(versionId: Long): List<PolicyReminder> = jpa.findByVersionId(versionId)
}
