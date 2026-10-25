package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.user.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JwtServiceTest {

    private val jwtService = JwtService(
        secret = "test-only-secret-value-at-least-32-bytes-long-for-hs256",
        expirationMs = 60_000,
    )

    @Test
    fun `token round-trips the user's phone as subject`() {
        val user = User(
            name = "Asha Rao",
            phone = "9999999999",
            passwordHash = "irrelevant-for-this-test",
        ).apply { id = 1L }

        val token = jwtService.generateToken(user)

        assertEquals(user.phone, jwtService.extractPhone(token))
    }
}
