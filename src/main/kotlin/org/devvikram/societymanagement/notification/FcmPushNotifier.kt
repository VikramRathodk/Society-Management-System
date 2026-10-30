package org.devvikram.societymanagement.notification

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

// Delivers through Firebase Cloud Messaging (HTTP v1). Same best-effort contract as the interface:
// every failure is logged and swallowed. Tokens FCM reports as gone are announced via
// InvalidPushTokens so they stop being stored.
@Component
@ConditionalOnProperty(name = ["app.push.provider"], havingValue = "fcm")
class FcmPushNotifier(
    private val messaging: FirebaseMessaging,
    private val events: ApplicationEventPublisher,
    @Value("\${app.push.android-channel-id:society_default}") private val androidChannelId: String,
) : PushNotifier {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(tokens: Collection<String>, title: String, body: String, data: Map<String, String>) {
        val targets = tokens.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return

        val dead = mutableSetOf<String>()
        targets.chunked(MAX_TOKENS_PER_CALL).forEach { batch ->
            try {
                val result = messaging.sendEachForMulticast(message(batch, title, body, data))
                // Responses are in the same order as the tokens.
                result.responses.forEachIndexed { i, response ->
                    if (response.isSuccessful) return@forEachIndexed
                    val code = response.exception?.messagingErrorCode
                    if (code in DEAD_TOKEN_CODES) dead += batch[i]
                    else log.warn("[push] FCM rejected a token: {} {}", code, response.exception?.message)
                }
                log.debug("[push] FCM sent {}/{}: {}", result.successCount, batch.size, title)
            } catch (e: Exception) {
                log.warn("[push] FCM batch of {} failed: {}", batch.size, e.message, e)
            }
        }

        if (dead.isNotEmpty()) {
            try {
                events.publishEvent(InvalidPushTokens(dead))
            } catch (e: Exception) {
                log.warn("[push] could not clear {} dead token(s): {}", dead.size, e.message, e)
            }
        }
    }

    private fun message(tokens: List<String>, title: String, body: String, data: Map<String, String>) =
        MulticastMessage.builder()
            .addAllTokens(tokens)
            .setNotification(Notification.builder().setTitle(title).setBody(body).build())
            .putAllData(data)
            .setAndroidConfig(
                AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(AndroidNotification.builder().setChannelId(androidChannelId).build())
                    .build(),
            )
            .setApnsConfig(ApnsConfig.builder().setAps(Aps.builder().setSound("default").build()).build())
            .build()

    companion object {
        // FCM's limit for one multicast call.
        const val MAX_TOKENS_PER_CALL = 500

        // Only codes that mean "this token is permanently useless". INVALID_ARGUMENT is left out on
        // purpose: it is also returned for a bad payload, which must not wipe every token.
        private val DEAD_TOKEN_CODES = setOf(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.SENDER_ID_MISMATCH)
    }
}
