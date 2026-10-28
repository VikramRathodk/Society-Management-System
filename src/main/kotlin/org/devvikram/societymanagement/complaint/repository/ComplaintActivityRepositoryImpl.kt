package org.devvikram.societymanagement.complaint.repository

import org.devvikram.societymanagement.complaint.model.ComplaintActivity
import org.springframework.stereotype.Repository

@Repository
class ComplaintActivityRepositoryImpl(
    private val complaintActivityJpaRepository: ComplaintActivityJpaRepository,
) : ComplaintActivityRepository {
    override fun save(activity: ComplaintActivity): ComplaintActivity = complaintActivityJpaRepository.save(activity)

    override fun findByComplaintId(complaintId: Long): List<ComplaintActivity> =
        complaintActivityJpaRepository.findWithActorByComplaintId(complaintId)
}
