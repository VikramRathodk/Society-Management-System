package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class JwtServiceTest {

    private val jwtService = JwtService(
        secret = "test-only-secret-value-at-least-32-bytes-long-for-hs256",
        expirationMs = 60_000,
    )

    @Test
    fun `token round-trips the user's phone as subject`() {
        val society = Society(name = "Green Valley").apply { id = UUID.randomUUID() }
        val user = AppUser(
            name = "Asha Rao",
            phone = "9999999999",
            passwordHash = "irrelevant-for-this-test",
            role = Role.ADMIN,
            society = society,
        ).apply { id = UUID.randomUUID() }

        val token = jwtService.generateToken(user)

        assertEquals(user.phone, jwtService.extractPhone(token))
    }
}
