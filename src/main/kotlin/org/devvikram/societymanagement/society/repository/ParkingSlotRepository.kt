package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.ParkingSlot

interface ParkingSlotRepository {
    fun save(parkingSlot: ParkingSlot): ParkingSlot
    fun findById(id: Long): ParkingSlot?
    fun findAll(): List<ParkingSlot>
}
