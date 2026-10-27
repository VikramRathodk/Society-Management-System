package org.devvikram.societymanagement.authorization.repository

import org.devvikram.societymanagement.authorization.model.Role
import org.springframework.stereotype.Repository

@Repository
class RoleRepositoryImpl(private val roleJpaRepository: RoleJpaRepository) : RoleRepository {
    override fun save(role: Role): Role = roleJpaRepository.save(role)
    override fun findByCode(code: String): Role? = roleJpaRepository.findByCode(code)
    override fun findByCodes(codes: Collection<String>): List<Role> = roleJpaRepository.findByCodeIn(codes)
    override fun findAll(): List<Role> = roleJpaRepository.findAll()
    override fun delete(role: Role) = roleJpaRepository.delete(role)
}
