package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.BillStatus
import org.devvikram.societymanagement.maintenance.model.MaintenanceBill
import org.devvikram.societymanagement.user.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

interface MaintenanceBillJpaRepository : JpaRepository<MaintenanceBill, Long> {
    @Query(
        "select distinct b from MaintenanceBill b join fetch b.cycle join fetch b.society join fetch b.flat f " +
            "join fetch f.wing left join fetch b.statusChangedBy left join fetch b.lines where b.id = :id"
    )
    fun findWithDetailsById(@Param("id") id: Long): MaintenanceBill?

    // Oldest due first: the order payments are applied in.
    @Query(
        "select b from MaintenanceBill b join fetch b.cycle c join fetch b.society join fetch b.flat f join fetch f.wing " +
            "where f.id = :flatId and b.status in :statuses and b.total > b.amountPaid " +
            "order by c.dueDate, c.periodStart, b.id"
    )
    fun findOpenByFlatId(
        @Param("flatId") flatId: Long,
        @Param("statuses") statuses: Collection<BillStatus>,
    ): List<MaintenanceBill>

    @Query(
        "select b from MaintenanceBill b join fetch b.cycle c join fetch b.flat f " +
            "where b.society.id = :societyId and b.status in :statuses and b.total > b.amountPaid " +
            "order by c.dueDate, c.periodStart, b.id"
    )
    fun findOpenBySocietyId(
        @Param("societyId") societyId: Long,
        @Param("statuses") statuses: Collection<BillStatus>,
    ): List<MaintenanceBill>

    @Query(
        "select new org.devvikram.societymanagement.maintenance.repository.LateChargeMark(" +
            "l.sourceBillId, max(l.chargedThrough)) from MaintenanceBillLine l " +
            "where l.sourceBillId in :billIds and l.bill.status <> :cancelled group by l.sourceBillId"
    )
    fun lateChargeMarks(
        @Param("billIds") billIds: Collection<Long>,
        @Param("cancelled") cancelled: BillStatus,
    ): List<LateChargeMark>

    @Query("select count(b) from MaintenanceBill b where b.cycle.id = :cycleId and b.amountPaid > 0")
    fun countPaidInCycle(@Param("cycleId") cycleId: Long): Long

    @Query(
        "select new org.devvikram.societymanagement.maintenance.repository.FlatDues(" +
            "f.id, w.name, f.flatNumber, count(b), sum(b.total - b.amountPaid), " +
            "sum(case when c.dueDate < :overdueBefore then b.total - b.amountPaid else 0bd end), min(c.dueDate)) " +
            "from MaintenanceBill b join b.cycle c join b.flat f join f.wing w " +
            "where b.society.id = :societyId and b.status in :statuses and b.total > b.amountPaid " +
            "group by f.id, w.name, f.flatNumber order by w.name, f.flatNumber"
    )
    fun dues(
        @Param("societyId") societyId: Long,
        @Param("statuses") statuses: Collection<BillStatus>,
        @Param("overdueBefore") overdueBefore: LocalDate,
    ): List<FlatDues>

    @Query(
        "select new org.devvikram.societymanagement.maintenance.repository.CycleBillTotals(" +
            "b.cycle.id, b.status, count(b), sum(b.total), sum(b.amountPaid)) " +
            "from MaintenanceBill b where b.cycle.id in :cycleIds group by b.cycle.id, b.status"
    )
    fun totalsByCycle(@Param("cycleIds") cycleIds: Collection<Long>): List<CycleBillTotals>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update MaintenanceBill b set b.status = :to, b.statusChangedBy = :by, b.statusChangedAt = :at, " +
            "b.statusReason = :reason where b.id = :id and b.status in :from"
    )
    fun changeStatus(
        @Param("id") id: Long,
        @Param("from") from: Collection<BillStatus>,
        @Param("to") to: BillStatus,
        @Param("by") by: User,
        @Param("at") at: Instant,
        @Param("reason") reason: String,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update MaintenanceBill b set b.status = :to, b.statusChangedBy = :by, b.statusChangedAt = :at, " +
            "b.statusReason = :reason where b.cycle.id = :cycleId and b.status in :from"
    )
    fun changeStatusByCycle(
        @Param("cycleId") cycleId: Long,
        @Param("from") from: Collection<BillStatus>,
        @Param("to") to: BillStatus,
        @Param("by") by: User,
        @Param("at") at: Instant,
        @Param("reason") reason: String,
    ): Int
}
