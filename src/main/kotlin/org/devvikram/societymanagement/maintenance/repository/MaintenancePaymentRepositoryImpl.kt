package org.devvikram.societymanagement.maintenance.repository

import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.maintenance.model.MaintenancePayment
import org.devvikram.societymanagement.maintenance.model.PaymentAllocation
import org.devvikram.societymanagement.maintenance.model.PaymentStatus
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class MaintenancePaymentRepositoryImpl(
    private val paymentJpaRepository: MaintenancePaymentJpaRepository,
    private val entityManager: EntityManager,
) : MaintenancePaymentRepository {
    override fun save(payment: MaintenancePayment): MaintenancePayment = paymentJpaRepository.save(payment)

    override fun findById(id: Long): MaintenancePayment? = paymentJpaRepository.findWithDetailsById(id)

    override fun findSocietyId(id: Long): Long? = paymentJpaRepository.findSocietyId(id)

    // Built per call so each optional filter is either in the query or absent.
    override fun search(search: PaymentSearch): List<MaintenancePayment> {
        if (search.flatIds?.isEmpty() == true) return emptyList()
        val filters = buildList {
            add("p.society.id = :societyId")
            if (search.flatIds != null) add("p.flat.id in :flatIds")
            if (search.from != null) add("p.paidOn >= :from")
            if (search.to != null) add("p.paidOn <= :to")
            if (!search.includeVoid) add("p.status = :recorded")
        }
        val query = entityManager.createQuery(
            "select p from MaintenancePayment p join fetch p.society join fetch p.flat f join fetch f.wing " +
                "join fetch p.recordedBy left join fetch p.voidedBy where ${filters.joinToString(" and ")} " +
                "order by p.paidOn desc, p.id desc",
            MaintenancePayment::class.java,
        )
        query.setParameter("societyId", search.societyId)
        search.flatIds?.let { query.setParameter("flatIds", it) }
        search.from?.let { query.setParameter("from", it) }
        search.to?.let { query.setParameter("to", it) }
        if (!search.includeVoid) query.setParameter("recorded", PaymentStatus.RECORDED)
        return query.setFirstResult(search.page * search.size).setMaxResults(search.size).resultList
    }

    override fun findForExport(societyId: Long, from: LocalDate, to: LocalDate): List<MaintenancePayment> =
        paymentJpaRepository.findForExport(societyId, from, to)

    override fun countBySocietyId(societyId: Long): Long = paymentJpaRepository.countBySocietyId(societyId)

    override fun activeAllocationsOfBill(billId: Long): List<PaymentAllocation> =
        paymentJpaRepository.allocationsOfBill(billId, PaymentStatus.RECORDED)
}
