package org.devvikram.societymanagement.maintenance.repository

import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.maintenance.model.MaintenancePayment
import org.devvikram.societymanagement.maintenance.model.PaymentAllocation
import org.devvikram.societymanagement.maintenance.model.PaymentStatus
import org.springframework.stereotype.Repository
import java.math.BigDecimal
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

    override fun creditsOfFlat(flatId: Long): List<PaymentCredit> =
        creditQuery("p.flat_id", flatId).map { PaymentCredit((it[0] as Number).toLong(), decimal(it[2])) }

    override fun creditByFlat(societyId: Long): Map<Long, BigDecimal> =
        creditQuery("p.society_id", societyId).groupBy({ (it[1] as Number).toLong() }, { decimal(it[2]) })
            .mapValues { (_, credits) -> credits.fold(BigDecimal.ZERO) { sum, c -> sum + c }.setScale(2) }

    override fun refundedFrom(paymentId: Long): BigDecimal = decimal(
        entityManager.createNativeQuery(
            "select coalesce(sum(ra.amount), 0) from maintenance_refund_allocation ra " +
                "join maintenance_refund r on r.id = ra.refund_id " +
                "where ra.payment_id = :id and r.status in ('PENDING_APPROVAL','COMPLETED')"
        ).setParameter("id", paymentId).singleResult!!
    )

    override fun addAllocation(allocation: PaymentAllocation): PaymentAllocation {
        entityManager.persist(allocation)
        return allocation
    }

    override fun creditAllocationsOfCycle(cycleId: Long): List<PaymentAllocation> =
        entityManager.createQuery(
            "select a from PaymentAllocation a join fetch a.payment p join fetch a.bill b " +
                "where a.fromCredit = true and p.status = :recorded and b.cycle.id = :cycleId order by a.id",
            PaymentAllocation::class.java,
        ).setParameter("recorded", PaymentStatus.RECORDED).setParameter("cycleId", cycleId).resultList

    override fun deleteAllocations(allocations: Collection<PaymentAllocation>) {
        allocations.forEach { allocation ->
            allocation.payment.allocations.remove(allocation)
            entityManager.remove(allocation)
        }
    }

    // Rows of (payment id, flat id, credit) for RECORDED payments where `column` = id, with credit > 0,
    // oldest first. Native: the refund side has no association from the payment.
    private fun creditQuery(column: String, id: Long): List<Array<Any>> {
        @Suppress("UNCHECKED_CAST")
        return entityManager.createNativeQuery(
            """
            select p.id, p.flat_id, p.amount - coalesce(a.total, 0) - coalesce(r.total, 0) as credit
            from maintenance_payment p
            left join (select payment_id, sum(amount) total from maintenance_payment_allocation group by payment_id) a
                on a.payment_id = p.id
            left join (select ra.payment_id, sum(ra.amount) total from maintenance_refund_allocation ra
                       join maintenance_refund rf on rf.id = ra.refund_id
                       where rf.status in ('PENDING_APPROVAL','COMPLETED') group by ra.payment_id) r
                on r.payment_id = p.id
            where $column = :id and p.status = 'RECORDED'
              and p.amount - coalesce(a.total, 0) - coalesce(r.total, 0) > 0
            order by p.paid_on, p.id
            """.trimIndent()
        ).setParameter("id", id)
            .resultList as List<Array<Any>>
    }

    private fun decimal(value: Any): BigDecimal = when (value) {
        is BigDecimal -> value
        is Number -> BigDecimal(value.toString())
        else -> error("Unexpected number type ${value::class}")
    }
}
