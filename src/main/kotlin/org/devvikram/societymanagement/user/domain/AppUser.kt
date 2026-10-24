package org.devvikram.societymanagement.user.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// Identity only. What the user may do, and where, lives in UserRole (one row per role per scope).
@Entity
@Table(name = "app_user")
class AppUser(
    @Column(nullable = false)
    var name: String,

    @Column(nullable = false, unique = true)
    var phone: String,

    var email: String? = null,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(name = "fcm_token")
    var fcmToken: String? = null,

    @Column(name = "is_active")
    var isActive: Boolean = true,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at")
    var createdAt: Instant? = null

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}
