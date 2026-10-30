package org.devvikram.societymanagement.notification

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File

// Only loaded with app.push.provider=fcm, so dev runs and tests never need Firebase credentials.
@Configuration
@ConditionalOnProperty(name = ["app.push.provider"], havingValue = "fcm")
class FirebaseConfig {

    // A blank path falls back to Application Default Credentials (GOOGLE_APPLICATION_CREDENTIALS,
    // or the attached service account when running on GCP).
    @Bean
    fun firebaseMessaging(@Value("\${app.firebase.credentials-path:}") credentialsPath: String): FirebaseMessaging {
        val credentials = if (credentialsPath.isBlank()) {
            GoogleCredentials.getApplicationDefault()
        } else {
            val file = File(credentialsPath)
            check(file.isFile) { "Firebase credentials not found at ${file.absolutePath} (app.firebase.credentials-path)" }
            file.inputStream().use { GoogleCredentials.fromStream(it) }
        }
        val app = FirebaseApp.getApps().firstOrNull()
            ?: FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(credentials).build())
        return FirebaseMessaging.getInstance(app)
    }
}
