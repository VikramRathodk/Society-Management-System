package org.devvikram.societymanagement.security.permission

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
import jakarta.persistence.Table
import org.devvikram.societymanagement.user.domain.Role
import java.util.UUID

@Entity
@Table(name = "role_permission")
class RolePermission(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: Role,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id", nullable = false)
    var permission: Permission,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}
