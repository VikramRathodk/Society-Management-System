package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.ChargeHead
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChargeHeadJpaRepository : JpaRepository<ChargeHead, Long> {
    @Query("select h from ChargeHead h join fetch h.society where h.id = :id")
    fun findWithDetailsById(@Param("id") id: Long): ChargeHead?

    @Query("select h from ChargeHead h join fetch h.society s where s.id = :societyId order by h.sortOrder, h.name")
    fun findBySocietyId(@Param("societyId") societyId: Long): List<ChargeHead>
}
