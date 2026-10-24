package org.devvikram.societymanagement.visitor.infrastructure

import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.visitor.domain.VisitorEntry
import org.devvikram.societymanagement.visitor.domain.VisitorStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface VisitorEntryJpaRepository : JpaRepository<VisitorEntry, Long> {
    @Query(
        "select e from VisitorEntry e join fetch e.society join fetch e.flat join fetch e.loggedBy " +
            "left join fetch e.respondedBy where e.id = :id"
    )
    fun findWithDetailsById(@Param("id") id: Long): VisitorEntry?

    // clearAutomatically: the update bypasses the persistence context, so drop stale copies of the entry.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update VisitorEntry e set e.status = :to, e.respondedBy = :responder, " +
            "e.respondedAsOverride = :asOverride, e.respondedAt = :at where e.id = :id and e.status = :from"
    )
    fun respond(
        @Param("id") id: Long,
        @Param("from") from: VisitorStatus,
        @Param("to") to: VisitorStatus,
        @Param("responder") responder: AppUser,
        @Param("asOverride") asOverride: Boolean,
        @Param("at") at: Instant,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update VisitorEntry e set e.status = :to, e.enteredAt = :at where e.id = :id and e.status = :from")
    fun markEntered(
        @Param("id") id: Long,
        @Param("from") from: VisitorStatus,
        @Param("to") to: VisitorStatus,
        @Param("at") at: Instant,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update VisitorEntry e set e.status = :to, e.exitedAt = :at where e.id = :id and e.status = :from")
    fun markExited(
        @Param("id") id: Long,
        @Param("from") from: VisitorStatus,
        @Param("to") to: VisitorStatus,
        @Param("at") at: Instant,
    ): Int
}
