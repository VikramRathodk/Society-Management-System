package org.devvikram.societymanagement.maintenance.repository

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.devvikram.societymanagement.maintenance.model.CycleStatus
import org.devvikram.societymanagement.maintenance.model.MaintenanceCycle
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.user.model.User
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

@Repository
class MaintenanceCycleRepositoryImpl(
    private val cycleJpaRepository: MaintenanceCycleJpaRepository,
    private val entityManager: EntityManager,
) : MaintenanceCycleRepository {
    override fun save(cycle: MaintenanceCycle): MaintenanceCycle = cycleJpaRepository.save(cycle)

    override fun findById(id: Long): MaintenanceCycle? = cycleJpaRepository.findWithDetailsById(id)

    override fun findBySocietyId(societyId: Long): List<MaintenanceCycle> = cycleJpaRepository.findBySocietyId(societyId)

    override fun findIssuedOverlapping(societyId: Long, start: LocalDate, end: LocalDate): List<MaintenanceCycle> =
        cycleJpaRepository.findOverlapping(societyId, CycleStatus.ISSUED, start, end)

    override fun findLatestIssued(societyId: Long): MaintenanceCycle? =
        cycleJpaRepository.findLatest(societyId, CycleStatus.ISSUED, PageRequest.of(0, 1)).firstOrNull()

    override fun lockSociety(societyId: Long) {
        entityManager.find(Society::class.java, societyId, LockModeType.PESSIMISTIC_WRITE)
            ?: throw NoSuchElementException("Society $societyId not found")
    }

    override fun cancel(id: Long, by: User, at: Instant, reason: String): Boolean =
        cycleJpaRepository.changeStatus(id, CycleStatus.ISSUED, CycleStatus.CANCELLED, by, at, reason) == 1
}
