package org.devvikram.societymanagement.security.role

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

// Plain port so services that need role lookups can be unit-tested against in-memory fakes.
interface RoleRepository {
    fun save(role: Role): Role
    fun findByCode(code: String): Role?
    fun findByCodes(codes: Collection<String>): List<Role>
    fun findAll(): List<Role>
    fun delete(role: Role)
}

interface RoleJpaRepository : JpaRepository<Role, Long> {
    fun findByCode(code: String): Role?
    fun findByCodeIn(codes: Collection<String>): List<Role>
}

@Repository
class RoleRepositoryImpl(private val roleJpaRepository: RoleJpaRepository) : RoleRepository {
    override fun save(role: Role): Role = roleJpaRepository.save(role)
    override fun findByCode(code: String): Role? = roleJpaRepository.findByCode(code)
    override fun findByCodes(codes: Collection<String>): List<Role> = roleJpaRepository.findByCodeIn(codes)
    override fun findAll(): List<Role> = roleJpaRepository.findAll()
    override fun delete(role: Role) = roleJpaRepository.delete(role)
}
