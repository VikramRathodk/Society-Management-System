package org.devvikram.societymanagement.complaint.repository

import org.devvikram.societymanagement.complaint.model.ComplaintActivity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ComplaintActivityJpaRepository : JpaRepository<ComplaintActivity, Long> {
    // Oldest first; id breaks ties between steps recorded in the same instant.
    @Query(
        "select a from ComplaintActivity a join fetch a.actor where a.complaint.id = :complaintId " +
            "order by a.createdAt, a.id"
    )
    fun findWithActorByComplaintId(@Param("complaintId") complaintId: Long): List<ComplaintActivity>
}
