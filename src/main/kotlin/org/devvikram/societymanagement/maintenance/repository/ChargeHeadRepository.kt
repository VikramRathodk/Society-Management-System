package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.ChargeHead

// Reads return heads with their society loaded (open-in-view is off).
interface ChargeHeadRepository {
    fun save(head: ChargeHead): ChargeHead
    fun findById(id: Long): ChargeHead?
    // In bill order: sortOrder, then name.
    fun findBySocietyId(societyId: Long): List<ChargeHead>
    fun delete(head: ChargeHead)
}
