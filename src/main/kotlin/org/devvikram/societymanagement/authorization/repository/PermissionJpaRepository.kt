package org.devvikram.societymanagement.authorization.repository

import org.devvikram.societymanagement.authorization.model.Permission
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface PermissionJpaRepository : JpaRepository<Permission, Long> {
    @Query("select p.code from Permission p")
    fun findAllCodes(): Set<String>

    fun findByCodeIn(codes: Collection<String>): List<Permission>
}
