package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.StaffMember
import org.springframework.data.jpa.repository.JpaRepository

interface StaffMemberJpaRepository : JpaRepository<StaffMember, Long>
