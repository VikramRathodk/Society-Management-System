package org.devvikram.societymanagement.authorization.repository

import org.devvikram.societymanagement.authorization.model.Role

// Plain port so services that need role lookups can be unit-tested against in-memory fakes.
interface RoleRepository {
    fun save(role: Role): Role
    fun findByCode(code: String): Role?
    fun findByCodes(codes: Collection<String>): List<Role>
    fun findAll(): List<Role>
    fun delete(role: Role)
}
