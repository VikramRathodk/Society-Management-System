package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.MaintenanceCycle
import org.devvikram.societymanagement.user.model.User
import java.time.Instant
import java.time.LocalDate

// Reads return cycles with society, issuedBy and cancelledBy loaded (open-in-view is off).
interface MaintenanceCycleRepository {
    fun save(cycle: MaintenanceCycle): MaintenanceCycle
    fun findById(id: Long): MaintenanceCycle?
    // Newest period first, regular and opening-balance cycles alike.
    fun findBySocietyId(societyId: Long): List<MaintenanceCycle>
    // Issued regular cycles of the society whose period shares at least one day with [start, end].
    fun findIssuedOverlapping(societyId: Long, start: LocalDate, end: LocalDate): List<MaintenanceCycle>
    // The issued regular cycle with the latest period, if any.
    fun findLatestIssued(societyId: Long): MaintenanceCycle?
    // The society's issued opening-balance cycle, if any.
    fun findIssuedOpening(societyId: Long): MaintenanceCycle?
    // Row lock on the society until the transaction ends. Every change to a society's bills or payments
    // takes it, so issuing, paying, waiving and cancelling never interleave.
    fun lockSociety(societyId: Long)
    // ISSUED → CANCELLED as one conditional statement; false if it was no longer issued.
    fun cancel(id: Long, by: User, at: Instant, reason: String): Boolean
}
