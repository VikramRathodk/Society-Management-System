package org.devvikram.societymanagement.complaint.repository

import org.devvikram.societymanagement.complaint.model.ComplaintActivity

// Reads return steps with the actor loaded, oldest first.
interface ComplaintActivityRepository {
    fun save(activity: ComplaintActivity): ComplaintActivity
    fun findByComplaintId(complaintId: Long): List<ComplaintActivity>
}
