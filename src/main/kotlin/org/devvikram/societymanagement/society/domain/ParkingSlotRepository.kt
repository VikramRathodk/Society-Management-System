package org.devvikram.societymanagement.society.domain


interface ParkingSlotRepository {
    fun save(parkingSlot: ParkingSlot): ParkingSlot
    fun findById(id: Long): ParkingSlot?
    fun findAll(): List<ParkingSlot>
}
