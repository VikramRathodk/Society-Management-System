package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Society
import org.springframework.data.jpa.repository.JpaRepository

interface SocietyJpaRepository : JpaRepository<Society, Long>
