package org.devvikram.societymanagement.complaint.repository

import org.devvikram.societymanagement.complaint.model.Complaint
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import org.devvikram.societymanagement.user.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface ComplaintJpaRepository : JpaRepository<Complaint, Long> {
    @Query(
        "select c from Complaint c join fetch c.society join fetch c.flat join fetch c.raisedBy " +
            "left join fetch c.updatedBy where c.id = :id"
    )
    fun findWithDetailsById(@Param("id") id: Long): Complaint?

    // A single statement so Postgres cascades the timeline (complaint_activity ON DELETE CASCADE)
    // instead of the persistence context deciding.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Complaint c where c.id = :id")
    fun deleteDirectly(@Param("id") id: Long): Int

    // clearAutomatically: the update bypasses the persistence context, so drop stale copies of the complaint.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Complaint c set c.status = :to, c.updatedBy = :by, c.updatedAt = :at " +
            "where c.id = :id and c.status in :from"
    )
    fun changeStatus(
        @Param("id") id: Long,
        @Param("from") from: Collection<ComplaintStatus>,
        @Param("to") to: ComplaintStatus,
        @Param("by") by: User,
        @Param("at") at: Instant,
    ): Int
}
