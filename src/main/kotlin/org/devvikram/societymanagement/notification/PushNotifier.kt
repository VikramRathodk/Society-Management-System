package org.devvikram.societymanagement.notification

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

// Sends a push notification to devices (FCM registration tokens). Failures must not propagate:
// a notification is best-effort and never undoes the action that triggered it.
interface PushNotifier {
    fun send(tokens: Collection<String>, title: String, body: String, data: Map<String, String> = emptyMap())
}

// Default (app.push.provider=logging or unset): logs what would be sent, so dev runs and tests need
// no Firebase. app.push.provider=fcm swaps in FcmPushNotifier; callers don't change.
@Component
@ConditionalOnProperty(name = ["app.push.provider"], havingValue = "logging", matchIfMissing = true)
class LoggingPushNotifier : PushNotifier {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(tokens: Collection<String>, title: String, body: String, data: Map<String, String>) {
        log.info("[push] to {} device(s): {} — {} {}", tokens.size, title, body, data)
    }
}
