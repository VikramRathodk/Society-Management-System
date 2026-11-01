package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.CycleStatus
import org.devvikram.societymanagement.maintenance.model.MaintenanceCycle
import org.devvikram.societymanagement.user.model.User
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

interface MaintenanceCycleJpaRepository : JpaRepository<MaintenanceCycle, Long> {
    @Query(
        "select c from MaintenanceCycle c join fetch c.society join fetch c.issuedBy left join fetch c.cancelledBy " +
            "where c.id = :id"
    )
    fun findWithDetailsById(@Param("id") id: Long): MaintenanceCycle?

    @Query(
        "select c from MaintenanceCycle c join fetch c.society s join fetch c.issuedBy left join fetch c.cancelledBy " +
            "where s.id = :societyId order by c.periodStart desc, c.id desc"
    )
    fun findBySocietyId(@Param("societyId") societyId: Long): List<MaintenanceCycle>

    @Query(
        "select c from MaintenanceCycle c where c.society.id = :societyId and c.status = :status " +
            "and c.periodStart <= :end and c.periodEnd >= :start"
    )
    fun findOverlapping(
        @Param("societyId") societyId: Long,
        @Param("status") status: CycleStatus,
        @Param("start") start: LocalDate,
        @Param("end") end: LocalDate,
    ): List<MaintenanceCycle>

    @Query(
        "select c from MaintenanceCycle c join fetch c.society s join fetch c.issuedBy " +
            "where s.id = :societyId and c.status = :status order by c.periodEnd desc"
    )
    fun findLatest(
        @Param("societyId") societyId: Long,
        @Param("status") status: CycleStatus,
        pageable: Pageable,
    ): List<MaintenanceCycle>

    // clearAutomatically: the update bypasses the persistence context, so drop stale copies of the cycle.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update MaintenanceCycle c set c.status = :to, c.cancelledBy = :by, c.cancelledAt = :at, " +
            "c.cancelReason = :reason where c.id = :id and c.status = :from"
    )
    fun changeStatus(
        @Param("id") id: Long,
        @Param("from") from: CycleStatus,
        @Param("to") to: CycleStatus,
        @Param("by") by: User,
        @Param("at") at: Instant,
        @Param("reason") reason: String,
    ): Int
}
