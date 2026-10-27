package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.ParkingSlot
import org.springframework.stereotype.Repository

@Repository
class ParkingSlotRepositoryImpl(
    private val parkingSlotJpaRepository: ParkingSlotJpaRepository,
) : ParkingSlotRepository {
    override fun save(parkingSlot: ParkingSlot): ParkingSlot = parkingSlotJpaRepository.save(parkingSlot)

    override fun findById(id: Long): ParkingSlot? = parkingSlotJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<ParkingSlot> = parkingSlotJpaRepository.findAll()
}
