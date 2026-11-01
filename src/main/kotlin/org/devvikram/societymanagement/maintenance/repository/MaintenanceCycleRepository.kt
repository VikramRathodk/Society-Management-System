package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.MaintenanceCycle
import org.devvikram.societymanagement.user.model.User
import java.time.Instant
import java.time.LocalDate

// Reads return cycles with society, issuedBy and cancelledBy loaded (open-in-view is off).
interface MaintenanceCycleRepository {
    fun save(cycle: MaintenanceCycle): MaintenanceCycle
    fun findById(id: Long): MaintenanceCycle?
    // Newest period first.
    fun findBySocietyId(societyId: Long): List<MaintenanceCycle>
    // Issued cycles of the society whose period shares at least one day with [start, end].
    fun findIssuedOverlapping(societyId: Long, start: LocalDate, end: LocalDate): List<MaintenanceCycle>
    // The issued cycle with the latest period, if any.
    fun findLatestIssued(societyId: Long): MaintenanceCycle?
    // Row lock on the society until the transaction ends, so two cycles can't be issued for it at once.
    fun lockSociety(societyId: Long)
    // ISSUED → CANCELLED as one conditional statement; false if it was no longer issued.
    fun cancel(id: Long, by: User, at: Instant, reason: String): Boolean
}
