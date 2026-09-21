package org.devvikram.societymanagement.society.domain

import java.util.UUID

interface StaffMemberRepository {
    fun save(staffMember: StaffMember): StaffMember
    fun findById(id: UUID): StaffMember?
    fun findAll(): List<StaffMember>
}
