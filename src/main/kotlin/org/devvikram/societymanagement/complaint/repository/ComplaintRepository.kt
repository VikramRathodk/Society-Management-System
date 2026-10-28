package org.devvikram.societymanagement.complaint.repository

import org.devvikram.societymanagement.complaint.model.Complaint
import org.devvikram.societymanagement.complaint.model.ComplaintCategory
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import org.devvikram.societymanagement.user.model.User
import java.time.Instant

// flatIds == null means every flat in the society; an empty set matches nothing.
data class ComplaintSearch(
    val societyId: Long,
    val flatIds: Set<Long>? = null,
    val status: ComplaintStatus? = null,
    val category: ComplaintCategory? = null,
    val page: Int = 0,
    val size: Int = 20,
)

// Reads return complaints with society, flat, raisedBy and updatedBy loaded (open-in-view is off).
// changeStatus is a conditional single-statement update: it applies only while the complaint is in
// one of the expected states and returns whether it did, so two committee members can't both move it.
interface ComplaintRepository {
    fun save(complaint: Complaint): Complaint
    fun findById(id: Long): Complaint?
    fun search(search: ComplaintSearch): List<Complaint>
    fun changeStatus(id: Long, from: Set<ComplaintStatus>, to: ComplaintStatus, by: User, at: Instant): Boolean
    fun delete(complaint: Complaint)
}
