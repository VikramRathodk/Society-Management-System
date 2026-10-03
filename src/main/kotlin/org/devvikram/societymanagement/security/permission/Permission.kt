package org.devvikram.societymanagement.security.permission

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "permission")
class Permission(
    @Column(nullable = false)
    var module: String,

    @Column(nullable = false)
    var feature: String,

    var subfeature: String? = null,

    @Column(nullable = false)
    var action: String,

    @Column(nullable = false, unique = true)
    var code: String,

    var description: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}
