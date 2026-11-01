package org.devvikram.societymanagement.maintenance

import org.devvikram.societymanagement.notification.PushNotifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

// Replaces the real push sender in the maintenance integration tests. Top-level (not nested in a test
// class) so every test importing it gets the same cached Spring context and connection pool.
@TestConfiguration
class RecordingPushConfig {
    @Bean
    @Primary
    fun recordingPushNotifier() = RecordingPushNotifier()
}

class RecordingPushNotifier : PushNotifier {
    data class Push(val tokens: Collection<String>, val title: String, val body: String, val data: Map<String, String>)

    val sent = mutableListOf<Push>()

    override fun send(tokens: Collection<String>, title: String, body: String, data: Map<String, String>) {
        sent += Push(tokens, title, body, data)
    }
}
