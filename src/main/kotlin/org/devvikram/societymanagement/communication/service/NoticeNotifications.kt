package org.devvikram.societymanagement.communication.service

import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

// Carries everything the message needs, so the listener never touches lazy associations.
data class NoticePosted(
    val noticeId: Long,
    val societyId: Long,
    val authorId: Long,
    val title: String,
)

// Runs only after the transaction commits, so nobody hears about a notice that was rolled back.
// Best-effort: a failure is logged, never surfaced to the author.
@Component
class NoticeNotificationListener(
    private val userRoleRepository: UserRoleRepository,
    private val userRepository: UserRepository,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Everyone holding a role in the society, except the author.
    @TransactionalEventListener
    fun onPosted(event: NoticePosted) {
        try {
            val tokens = userRepository.findAllById(userRoleRepository.findUserIdsBySocietyIds(listOf(event.societyId)))
                .filter { it.isActive && it.id != event.authorId }
                .mapNotNull { it.fcmToken }
                .distinct()
            pushNotifier.send(
                tokens,
                title = "New notice",
                body = event.title,
                data = mapOf("type" to "NOTICE_POSTED", "noticeId" to event.noticeId.toString()),
            )
        } catch (e: Exception) {
            log.warn("Push notification failed for notice {} posted", event.noticeId, e)
        }
    }
}
