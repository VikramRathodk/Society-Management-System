package org.devvikram.societymanagement.maintenance.repository

import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.maintenance.model.BillStatus
import org.devvikram.societymanagement.maintenance.model.MaintenanceBill
import org.devvikram.societymanagement.user.model.User
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class MaintenanceBillRepositoryImpl(
    private val billJpaRepository: MaintenanceBillJpaRepository,
    private val entityManager: EntityManager,
) : MaintenanceBillRepository {
    override fun saveAll(bills: List<MaintenanceBill>): List<MaintenanceBill> = billJpaRepository.saveAll(bills)

    override fun findById(id: Long): MaintenanceBill? = billJpaRepository.findWithDetailsById(id)

    // Built per call so each optional filter is either in the query or absent.
    override fun search(search: BillSearch): List<MaintenanceBill> {
        if (search.flatIds?.isEmpty() == true) return emptyList()
        val filters = buildList {
            add("b.society.id = :societyId")
            if (search.flatIds != null) add("b.flat.id in :flatIds")
            if (search.cycleId != null) add("b.cycle.id = :cycleId")
            if (search.status != null) add("b.status = :status")
        }
        val query = entityManager.createQuery(
            "select b from MaintenanceBill b join fetch b.cycle c join fetch b.society join fetch b.flat f " +
                "join fetch f.wing left join fetch b.statusChangedBy where ${filters.joinToString(" and ")} " +
                "order by c.periodStart desc, b.billNumber",
            MaintenanceBill::class.java,
        )
        query.setParameter("societyId", search.societyId)
        search.flatIds?.let { query.setParameter("flatIds", it) }
        search.cycleId?.let { query.setParameter("cycleId", it) }
        search.status?.let { query.setParameter("status", it) }
        return query.setFirstResult(search.page * search.size).setMaxResults(search.size).resultList
    }

    override fun totalsByCycle(cycleIds: Collection<Long>): List<CycleBillTotals> =
        if (cycleIds.isEmpty()) emptyList() else billJpaRepository.totalsByCycle(cycleIds)

    override fun changeStatus(
        id: Long, from: Set<BillStatus>, to: BillStatus, by: User, at: Instant, reason: String,
    ): Boolean = billJpaRepository.changeStatus(id, from, to, by, at, reason) == 1

    override fun cancelByCycle(cycleId: Long, from: Set<BillStatus>, by: User, at: Instant, reason: String): Int =
        billJpaRepository.changeStatusByCycle(cycleId, from, BillStatus.CANCELLED, by, at, reason)
}
