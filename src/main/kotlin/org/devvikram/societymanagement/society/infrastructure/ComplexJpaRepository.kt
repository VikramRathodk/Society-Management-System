package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Complex
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ComplexJpaRepository : JpaRepository<Complex, UUID>
