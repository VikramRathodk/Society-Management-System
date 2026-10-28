package org.devvikram.societymanagement.communication.repository

import org.devvikram.societymanagement.communication.model.Notice
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class NoticeRepositoryImpl(
    private val noticeJpaRepository: NoticeJpaRepository,
) : NoticeRepository {
    override fun save(notice: Notice): Notice = noticeJpaRepository.save(notice)

    override fun findById(id: Long): Notice? = noticeJpaRepository.findWithDetailsById(id)

    override fun findBySocietyId(societyId: Long, page: Int, size: Int): List<Notice> =
        noticeJpaRepository.findWithDetailsBySocietyId(societyId, PageRequest.of(page, size))

    override fun delete(notice: Notice) = noticeJpaRepository.delete(notice)
}
