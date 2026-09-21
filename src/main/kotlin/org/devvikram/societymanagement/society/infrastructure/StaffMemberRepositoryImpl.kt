package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.StaffMember
import org.devvikram.societymanagement.society.domain.StaffMemberRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class StaffMemberRepositoryImpl(
    private val staffMemberJpaRepository: StaffMemberJpaRepository,
) : StaffMemberRepository {
    override fun save(staffMember: StaffMember): StaffMember = staffMemberJpaRepository.save(staffMember)

    override fun findById(id: UUID): StaffMember? = staffMemberJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<StaffMember> = staffMemberJpaRepository.findAll()
}
