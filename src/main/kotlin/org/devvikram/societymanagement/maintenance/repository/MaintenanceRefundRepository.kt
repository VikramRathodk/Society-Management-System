package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.MaintenanceRefund
import org.devvikram.societymanagement.maintenance.model.RefundStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

// Reads return refunds with society, flat (and its wing) and allocations loaded.
interface MaintenanceRefundRepository {
    fun save(refund: MaintenanceRefund): MaintenanceRefund
    fun findById(id: Long): MaintenanceRefund?
    fun findSocietyId(id: Long): Long?
    // Newest first. flatIds == null means every flat; statuses == null every status.
    fun search(societyId: Long, flatIds: Set<Long>?, statuses: Set<RefundStatus>?): List<MaintenanceRefund>
    // Every refund the society ever recorded: the basis of refund numbers.
    fun countBySocietyId(societyId: Long): Long
}

interface MaintenanceRefundJpaRepository : JpaRepository<MaintenanceRefund, Long> {
    @Query(
        "select distinct r from MaintenanceRefund r join fetch r.society join fetch r.flat f join fetch f.wing " +
            "left join fetch r.allocations where r.id = :id"
    )
    fun findWithDetailsById(@Param("id") id: Long): MaintenanceRefund?

    @Query("select r.society.id from MaintenanceRefund r where r.id = :id")
    fun findSocietyId(@Param("id") id: Long): Long?

    @Query(
        "select distinct r from MaintenanceRefund r join fetch r.society s join fetch r.flat f join fetch f.wing " +
            "left join fetch r.allocations where s.id = :societyId order by r.id desc"
    )
    fun findBySocietyId(@Param("societyId") societyId: Long): List<MaintenanceRefund>

    @Query("select count(r) from MaintenanceRefund r where r.society.id = :societyId")
    fun countBySocietyId(@Param("societyId") societyId: Long): Long
}

@Repository
class MaintenanceRefundRepositoryImpl(
    private val refundJpaRepository: MaintenanceRefundJpaRepository,
) : MaintenanceRefundRepository {
    override fun save(refund: MaintenanceRefund): MaintenanceRefund = refundJpaRepository.save(refund)

    override fun findById(id: Long): MaintenanceRefund? = refundJpaRepository.findWithDetailsById(id)

    override fun findSocietyId(id: Long): Long? = refundJpaRepository.findSocietyId(id)

    // Refunds are few per society, so filtering happens here.
    override fun search(societyId: Long, flatIds: Set<Long>?, statuses: Set<RefundStatus>?): List<MaintenanceRefund> =
        refundJpaRepository.findBySocietyId(societyId)
            .filter { flatIds == null || it.flat.id in flatIds }
            .filter { statuses == null || it.status in statuses }

    override fun countBySocietyId(societyId: Long): Long = refundJpaRepository.countBySocietyId(societyId)
}
