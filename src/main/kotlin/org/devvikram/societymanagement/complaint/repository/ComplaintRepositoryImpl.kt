package org.devvikram.societymanagement.complaint.repository

import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.complaint.model.Complaint
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import org.devvikram.societymanagement.user.model.User
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ComplaintRepositoryImpl(
    private val complaintJpaRepository: ComplaintJpaRepository,
    private val entityManager: EntityManager,
) : ComplaintRepository {
    override fun save(complaint: Complaint): Complaint = complaintJpaRepository.save(complaint)

    override fun findById(id: Long): Complaint? = complaintJpaRepository.findWithDetailsById(id)

    // Built per call so each optional filter is either in the query or absent. Newest first; id breaks
    // ties between complaints raised in the same instant.
    override fun search(search: ComplaintSearch): List<Complaint> {
        if (search.flatIds?.isEmpty() == true) return emptyList()
        val filters = buildList {
            add("c.society.id = :societyId")
            if (search.flatIds != null) add("c.flat.id in :flatIds")
            if (search.status != null) add("c.status = :status")
            if (search.category != null) add("c.category = :category")
            if (search.createdFrom != null) add("c.createdAt >= :createdFrom")
            if (search.createdTo != null) add("c.createdAt < :createdTo")
        }
        val query = entityManager.createQuery(
            "select c from Complaint c join fetch c.society join fetch c.flat f join fetch f.wing join fetch c.raisedBy " +
                "left join fetch c.updatedBy where ${filters.joinToString(" and ")} " +
                "order by c.createdAt desc, c.id desc",
            Complaint::class.java,
        )
        query.setParameter("societyId", search.societyId)
        search.flatIds?.let { query.setParameter("flatIds", it) }
        search.status?.let { query.setParameter("status", it) }
        search.category?.let { query.setParameter("category", it) }
        search.createdFrom?.let { query.setParameter("createdFrom", it) }
        search.createdTo?.let { query.setParameter("createdTo", it) }
        return query.setFirstResult(search.page * search.size).setMaxResults(search.size).resultList
    }

    override fun changeStatus(id: Long, from: Set<ComplaintStatus>, to: ComplaintStatus, by: User, at: Instant): Boolean =
        complaintJpaRepository.changeStatus(id, from, to, by, at) == 1

    override fun delete(complaint: Complaint) {
        complaintJpaRepository.deleteDirectly(complaint.id!!)
    }
}
