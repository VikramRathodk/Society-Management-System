package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.BillStatus
import org.devvikram.societymanagement.maintenance.model.MaintenanceBill
import org.devvikram.societymanagement.user.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface MaintenanceBillJpaRepository : JpaRepository<MaintenanceBill, Long> {
    @Query(
        "select distinct b from MaintenanceBill b join fetch b.cycle join fetch b.society join fetch b.flat f " +
            "join fetch f.wing left join fetch b.statusChangedBy left join fetch b.lines where b.id = :id"
    )
    fun findWithDetailsById(@Param("id") id: Long): MaintenanceBill?

    @Query(
        "select new org.devvikram.societymanagement.maintenance.repository.CycleBillTotals(" +
            "b.cycle.id, b.status, count(b), sum(b.total)) " +
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
