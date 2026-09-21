package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Wing
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WingJpaRepository : JpaRepository<Wing, UUID>
