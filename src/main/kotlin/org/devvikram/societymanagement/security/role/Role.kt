package org.devvikram.societymanagement.security.role

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import org.devvikram.societymanagement.security.permission.Permission
import java.time.Instant
import java.util.UUID

enum class RoleScope {
    // Held without a society (e.g. SUPER_ADMIN) — its permissions apply in every society.
    PLATFORM,

    // Held per society; a user gets one assignment row per society.
    SOCIETY,
}

// Roles are data, not code: new ones are created at runtime (RoleService) by a SUPER_ADMIN.
@Entity
@Table(name = "role")
class Role(
    @Column(nullable = false, unique = true)
    var code: String,

    @Column(nullable = false)
    var name: String,

    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_level", nullable = false)
    var scopeLevel: RoleScope,

    // An assignment of this role must name a flat (RESIDENT-like roles).
    @Column(name = "requires_flat", nullable = false)
    var requiresFlat: Boolean = false,

    // Grants every permission, including codes added after the role was created.
    @Column(name = "all_permissions", nullable = false)
    var allPermissions: Boolean = false,

    // Built-in roles keep their code and cannot be deleted.
    @Column(name = "is_system", nullable = false)
    var isSystem: Boolean = false,

    // Lower roles this role includes: it gets their permissions and may assign them.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "role_inheritance",
        joinColumns = [JoinColumn(name = "role_id")],
        inverseJoinColumns = [JoinColumn(name = "inherited_role_id")],
    )
    var inherits: MutableSet<Role> = mutableSetOf(),

    // Direct grants only; inherited permissions are resolved through `inherits`.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "role_permission",
        joinColumns = [JoinColumn(name = "role_id")],
        inverseJoinColumns = [JoinColumn(name = "permission_id")],
    )
    var permissions: MutableSet<Permission> = mutableSetOf(),
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

    // This role plus everything it inherits, transitively. The visited set makes it cycle-safe.
    fun selfAndInherited(): Set<Role> {
        val visited = linkedSetOf<Role>()
        val queue = ArrayDeque(listOf(this))
        while (queue.isNotEmpty()) {
            val role = queue.removeFirst()
            if (visited.add(role)) queue.addAll(role.inherits)
        }
        return visited
    }
}
