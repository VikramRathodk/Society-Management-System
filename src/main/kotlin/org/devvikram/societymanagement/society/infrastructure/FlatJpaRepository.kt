package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Flat
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FlatJpaRepository : JpaRepository<Flat, UUID>
