package org.devvikram.societymanagement.authorization.repository

import org.devvikram.societymanagement.authorization.model.Role
import org.springframework.data.jpa.repository.JpaRepository

interface RoleJpaRepository : JpaRepository<Role, Long> {
    fun findByCode(code: String): Role?
    fun findByCodeIn(codes: Collection<String>): List<Role>
}
