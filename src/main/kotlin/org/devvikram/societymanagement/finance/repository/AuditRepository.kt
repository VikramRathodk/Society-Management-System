package org.devvikram.societymanagement.finance.repository

import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.model.FinanceAuditEntry
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

// Insert and read only: there is deliberately no update or delete.
interface AuditRepository {
    fun add(entry: FinanceAuditEntry): FinanceAuditEntry
    // Newest first, optionally one entity type (and id).
    fun search(societyId: Long, entityType: AuditEntityType?, entityId: Long?, page: Int, size: Int): List<FinanceAuditEntry>
}

interface AuditJpaRepository : JpaRepository<FinanceAuditEntry, Long> {
    @Query(
        """
        select e from FinanceAuditEntry e
        where e.societyId = :s
          and (:type is null or e.entityType = :type)
          and (:entityId is null or e.entityId = :entityId)
        order by e.performedAt desc, e.id desc
        """
    )
    fun search(
        @Param("s") societyId: Long,
        @Param("type") entityType: AuditEntityType?,
        @Param("entityId") entityId: Long?,
        pageable: PageRequest,
    ): List<FinanceAuditEntry>
}

@Repository
class AuditRepositoryImpl(
    private val auditJpaRepository: AuditJpaRepository,
) : AuditRepository {
    override fun add(entry: FinanceAuditEntry): FinanceAuditEntry = auditJpaRepository.save(entry)

    override fun search(societyId: Long, entityType: AuditEntityType?, entityId: Long?, page: Int, size: Int) =
        auditJpaRepository.search(societyId, entityType, entityId, PageRequest.of(page, size))
}
