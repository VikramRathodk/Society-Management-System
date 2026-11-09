package org.devvikram.societymanagement.invitation.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// Link tokens: 32 random bytes, URL-safe (43 characters). Only their SHA-256 is stored.
object InvitationTokens {
    private val random = SecureRandom()

    fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    // "98•••••210": enough for the person to recognise their number, not enough to learn someone else's.
    fun maskPhone(phone: String): String =
        if (phone.length <= 5) "•".repeat(phone.length)
        else phone.take(2) + "•".repeat(phone.length - 5) + phone.takeLast(3)
}
