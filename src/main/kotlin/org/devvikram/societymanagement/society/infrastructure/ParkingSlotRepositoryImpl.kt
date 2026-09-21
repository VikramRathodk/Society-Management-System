package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.ParkingSlot
import org.devvikram.societymanagement.society.domain.ParkingSlotRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ParkingSlotRepositoryImpl(
    private val parkingSlotJpaRepository: ParkingSlotJpaRepository,
) : ParkingSlotRepository {
    override fun save(parkingSlot: ParkingSlot): ParkingSlot = parkingSlotJpaRepository.save(parkingSlot)

    override fun findById(id: UUID): ParkingSlot? = parkingSlotJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<ParkingSlot> = parkingSlotJpaRepository.findAll()
}
