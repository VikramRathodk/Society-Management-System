package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Complex
import org.springframework.data.jpa.repository.JpaRepository

interface ComplexJpaRepository : JpaRepository<Complex, Long>
