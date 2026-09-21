package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Society
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SocietyJpaRepository : JpaRepository<Society, UUID>
