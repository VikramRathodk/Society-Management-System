package org.devvikram.societymanagement.society.domain

import java.util.UUID

interface ParkingSlotRepository {
    fun save(parkingSlot: ParkingSlot): ParkingSlot
    fun findById(id: UUID): ParkingSlot?
    fun findAll(): List<ParkingSlot>
}
