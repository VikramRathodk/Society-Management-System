package org.devvikram.societymanagement.user.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import org.devvikram.societymanagement.society.domain.Complex
import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.Society
import java.time.Instant
import java.util.UUID

// chk_user_scope (exactly one of society/complex set) is a DB constraint, enforced at the
// service layer on write — not expressible as a JPA-level invariant.
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: Role,

    // null for GUARD/ADMIN; multiple app_user rows can share one flat_id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flat_id")
    var flat: Flat? = null,

    // only set when role = RESIDENT
    @Enumerated(EnumType.STRING)
    @Column(name = "resident_type")
    var residentType: ResidentType? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "society_id")
    var society: Society? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "complex_id")
    var complex: Complex? = null,

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
