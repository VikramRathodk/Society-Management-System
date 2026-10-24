package org.devvikram.societymanagement.society.domain


interface StaffMemberRepository {
    fun save(staffMember: StaffMember): StaffMember
    fun findById(id: Long): StaffMember?
    fun findAll(): List<StaffMember>
}
