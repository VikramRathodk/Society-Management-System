package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.StaffMember
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StaffMemberJpaRepository : JpaRepository<StaffMember, UUID>
