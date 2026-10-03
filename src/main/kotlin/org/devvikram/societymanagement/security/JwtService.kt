package org.devvikram.societymanagement.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.devvikram.societymanagement.user.domain.AppUser
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

    // Token carries only userId/role/societyId/complexId — never resolved permissions, so a
    // permission change takes effect immediately without waiting for the token to expire.
    fun generateToken(user: AppUser): String {
        val claims = buildMap<String, Any> {
            put("userId", user.id.toString())
            put("role", user.role.name)
            user.society?.id?.let { put("societyId", it.toString()) }
            user.complex?.id?.let { put("complexId", it.toString()) }
        }
        val now = Date()
        return Jwts.builder()
            .subject(user.phone)
            .claims(claims)
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .signWith(key)
            .compact()
    }

    fun extractPhone(token: String): String = parseClaims(token).subject

    private fun parseClaims(token: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
}
