package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.StaffMember

interface StaffMemberRepository {
    fun save(staffMember: StaffMember): StaffMember
    fun findById(id: Long): StaffMember?
    fun findAll(): List<StaffMember>
}
