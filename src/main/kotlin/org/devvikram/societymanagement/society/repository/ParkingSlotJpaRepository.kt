package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.ParkingSlot
import org.springframework.data.jpa.repository.JpaRepository

interface ParkingSlotJpaRepository : JpaRepository<ParkingSlot, Long>
