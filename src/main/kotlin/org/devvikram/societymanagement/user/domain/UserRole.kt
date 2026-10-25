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
import org.devvikram.societymanagement.security.role.Role
import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.Society
import java.time.Instant

// One role held by one user in one scope. society is null only for PLATFORM roles;
// flat/residentType are set only for roles with requiresFlat. Enforced in UserService.
@Entity
@Table(name = "user_role")
class UserRole(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    var role: Role,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "society_id")
    var society: Society? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flat_id")
    var flat: Flat? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "resident_type")
    var residentType: ResidentType? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at")
    var createdAt: Instant? = null

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}
