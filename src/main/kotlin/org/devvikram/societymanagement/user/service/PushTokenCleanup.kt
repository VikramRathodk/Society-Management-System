package org.devvikram.societymanagement.user.service

import org.devvikram.societymanagement.notification.InvalidPushTokens
import org.devvikram.societymanagement.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

// Forgets tokens the push provider says are gone, so they aren't sent to again.
@Component
class PushTokenCleanup(private val userRepository: UserRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    // REQUIRES_NEW: pushes are sent from after-commit listeners, where joining the finished
    // transaction would silently never commit this update.
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onInvalidTokens(event: InvalidPushTokens) {
        val cleared = userRepository.clearFcmTokens(event.tokens)
        log.info("[push] cleared {} dead token(s) from {} user(s)", event.tokens.size, cleared)
    }
}
