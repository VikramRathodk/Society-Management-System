package org.devvikram.societymanagement.notice.repository

import org.devvikram.societymanagement.notice.model.Notice

// Reads return notices with society, postedBy and updatedBy loaded (open-in-view is off).
interface NoticeRepository {
    fun save(notice: Notice): Notice
    fun findById(id: Long): Notice?
    // Newest first.
    fun findBySocietyId(societyId: Long, page: Int, size: Int): List<Notice>
    fun delete(notice: Notice)
}
