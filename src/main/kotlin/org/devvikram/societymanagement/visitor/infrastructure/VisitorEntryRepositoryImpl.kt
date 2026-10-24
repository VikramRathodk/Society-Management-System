package org.devvikram.societymanagement.visitor.infrastructure

import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.visitor.domain.VisitorEntry
import org.devvikram.societymanagement.visitor.domain.VisitorEntryRepository
import org.devvikram.societymanagement.visitor.domain.VisitorSearch
import org.devvikram.societymanagement.visitor.domain.VisitorStatus
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class VisitorEntryRepositoryImpl(
    private val visitorEntryJpaRepository: VisitorEntryJpaRepository,
    private val entityManager: EntityManager,
) : VisitorEntryRepository {
    override fun save(entry: VisitorEntry): VisitorEntry = visitorEntryJpaRepository.save(entry)

    override fun findById(id: Long): VisitorEntry? = visitorEntryJpaRepository.findWithDetailsById(id)

    // Built per call so each optional filter is either in the query or absent — no "(:x is null or ...)"
    // tricks. Newest first; id breaks ties between entries logged in the same instant.
    override fun search(search: VisitorSearch): List<VisitorEntry> {
        if (search.flatIds?.isEmpty() == true) return emptyList()
        val filters = buildList {
            add("e.society.id = :societyId")
            if (search.flatIds != null) add("e.flat.id in :flatIds")
            if (search.status != null) add("e.status = :status")
        }
        val query = entityManager.createQuery(
            "select e from VisitorEntry e join fetch e.society join fetch e.flat join fetch e.loggedBy " +
                "left join fetch e.respondedBy where ${filters.joinToString(" and ")} " +
                "order by e.loggedAt desc, e.id desc",
            VisitorEntry::class.java,
        )
        query.setParameter("societyId", search.societyId)
        search.flatIds?.let { query.setParameter("flatIds", it) }
        search.status?.let { query.setParameter("status", it) }
        return query.setFirstResult(search.page * search.size).setMaxResults(search.size).resultList
    }

    override fun respond(id: Long, decision: VisitorStatus, responder: AppUser, asOverride: Boolean, at: Instant): Boolean =
        visitorEntryJpaRepository.respond(id, VisitorStatus.PENDING, decision, responder, asOverride, at) == 1

    override fun markEntered(id: Long, at: Instant): Boolean =
        visitorEntryJpaRepository.markEntered(id, VisitorStatus.APPROVED, VisitorStatus.ENTERED, at) == 1

    override fun markExited(id: Long, at: Instant): Boolean =
        visitorEntryJpaRepository.markExited(id, VisitorStatus.ENTERED, VisitorStatus.EXITED, at) == 1

    override fun delete(entry: VisitorEntry) = visitorEntryJpaRepository.delete(entry)
}
