package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.StaffMember
import org.springframework.data.jpa.repository.JpaRepository

interface StaffMemberJpaRepository : JpaRepository<StaffMember, Long>
