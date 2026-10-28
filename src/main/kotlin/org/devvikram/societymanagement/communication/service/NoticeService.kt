package org.devvikram.societymanagement.communication.service

import org.devvikram.societymanagement.communication.model.Notice
import org.devvikram.societymanagement.communication.repository.NoticeRepository
import org.devvikram.societymanagement.society.repository.SocietyRepository
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class PostNoticeCommand(
    val societyId: Long,
    val title: String,
    val description: String? = null,
)

// null leaves a field unchanged; a blank description clears it.
data class UpdateNoticeCommand(
    val title: String? = null,
    val description: String? = null,
)

// Notice lifecycle and its invariants. *Who* may post, edit or delete (society, own-post rule,
// moderation) is decided by the caller (NoticeController), not here. Write methods return ids;
// callers re-read through getById so the response has every association loaded.
@Service
class NoticeService(
    private val noticeRepository: NoticeRepository,
    private val societyRepository: SocietyRepository,
    private val userRepository: UserRepository,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun post(command: PostNoticeCommand, authorId: Long): Long {
        require(command.title.isNotBlank()) { "Title must not be blank" }
        val society = societyRepository.findById(command.societyId)
            ?: throw NoSuchElementException("Society ${command.societyId} not found")
        val author = userRepository.findById(authorId) ?: throw NoSuchElementException("User $authorId not found")
        val notice = noticeRepository.save(
            Notice(
                society = society,
                title = command.title.trim(),
                description = command.description?.trim()?.ifEmpty { null },
                postedBy = author,
            )
        )
        events.publishEvent(NoticePosted(notice.id!!, society.id!!, author.id!!, notice.title))
        return notice.id!!
    }

    @Transactional
    fun update(id: Long, command: UpdateNoticeCommand, editorId: Long) {
        val notice = getById(id)
        command.title?.let {
            require(it.isNotBlank()) { "Title must not be blank" }
            notice.title = it.trim()
        }
        command.description?.let { notice.description = it.trim().ifEmpty { null } }
        notice.updatedBy = userRepository.findById(editorId) ?: throw NoSuchElementException("User $editorId not found")
        notice.updatedAt = Instant.now()
        noticeRepository.save(notice)
    }

    @Transactional
    fun delete(id: Long) {
        noticeRepository.delete(getById(id))
    }

    fun getById(id: Long): Notice =
        noticeRepository.findById(id) ?: throw NoSuchElementException("Notice $id not found")

    fun listBySociety(societyId: Long, page: Int, size: Int): List<Notice> =
        noticeRepository.findBySocietyId(societyId, page, size)
}
