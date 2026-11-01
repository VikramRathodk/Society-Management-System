package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.MaintenancePayment
import org.devvikram.societymanagement.maintenance.model.PaymentAllocation
import org.devvikram.societymanagement.maintenance.model.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface MaintenancePaymentJpaRepository : JpaRepository<MaintenancePayment, Long> {
    @Query(
        "select distinct p from MaintenancePayment p join fetch p.society join fetch p.flat f join fetch f.wing " +
            "join fetch p.recordedBy left join fetch p.voidedBy left join fetch p.allocations a " +
            "left join fetch a.bill where p.id = :id"
    )
    fun findWithDetailsById(@Param("id") id: Long): MaintenancePayment?

    @Query(
        "select distinct p from MaintenancePayment p join fetch p.flat f join fetch f.wing " +
            "join fetch p.recordedBy left join fetch p.voidedBy left join fetch p.allocations a left join fetch a.bill " +
            "where p.society.id = :societyId and p.paidOn between :from and :to order by p.paidOn, p.id"
    )
    fun findForExport(
        @Param("societyId") societyId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<MaintenancePayment>

    @Query("select p.society.id from MaintenancePayment p where p.id = :id")
    fun findSocietyId(@Param("id") id: Long): Long?

    @Query("select count(p) from MaintenancePayment p where p.society.id = :societyId")
    fun countBySocietyId(@Param("societyId") societyId: Long): Long

    @Query(
        "select a from PaymentAllocation a join fetch a.payment p where a.bill.id = :billId and p.status = :status " +
            "order by p.paidOn, p.id"
    )
    fun allocationsOfBill(@Param("billId") billId: Long, @Param("status") status: PaymentStatus): List<PaymentAllocation>
}
