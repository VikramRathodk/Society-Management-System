package org.devvikram.societymanagement.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.devvikram.societymanagement.user.domain.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtService(
    @Value("\${app.jwt.secret}") secret: String,
    @Value("\${app.jwt.expiration-ms}") private val expirationMs: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    // Token carries identity only — roles and permissions are loaded per request, so assigning,
    // revoking or redefining a role takes effect immediately without waiting for the token to expire.
    fun generateToken(user: User): String {
        val now = Date()
        return Jwts.builder()
            .subject(user.phone)
            .claim("userId", user.id.toString())
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .signWith(key)
            .compact()
    }

    fun extractPhone(token: String): String = parseClaims(token).subject

    private fun parseClaims(token: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
}
