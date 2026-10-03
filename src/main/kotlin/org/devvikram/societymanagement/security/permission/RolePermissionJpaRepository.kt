package org.devvikram.societymanagement.security.permission

import org.devvikram.societymanagement.user.domain.Role
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface RolePermissionJpaRepository : JpaRepository<RolePermission, UUID> {
    @Query("select rp.permission.code from RolePermission rp where rp.role = :role")
    fun findPermissionCodesByRole(@Param("role") role: Role): Set<String>
}
