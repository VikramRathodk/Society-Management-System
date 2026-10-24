package org.devvikram.societymanagement.common.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// Sends a push notification to devices (FCM registration tokens). Failures must not propagate:
// a notification is best-effort and never undoes the action that triggered it.
interface PushNotifier {
    fun send(tokens: Collection<String>, title: String, body: String, data: Map<String, String> = emptyMap())
}

// Placeholder until Firebase is set up: logs what would be sent. Replace with an FCM-backed
// PushNotifier bean; callers don't change.
@Component
class LoggingPushNotifier : PushNotifier {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(tokens: Collection<String>, title: String, body: String, data: Map<String, String>) {
        log.info("[push] to {} device(s): {} — {} {}", tokens.size, title, body, data)
    }
}
