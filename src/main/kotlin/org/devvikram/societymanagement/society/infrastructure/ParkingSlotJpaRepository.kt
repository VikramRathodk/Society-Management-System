package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.ParkingSlot
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ParkingSlotJpaRepository : JpaRepository<ParkingSlot, UUID>
