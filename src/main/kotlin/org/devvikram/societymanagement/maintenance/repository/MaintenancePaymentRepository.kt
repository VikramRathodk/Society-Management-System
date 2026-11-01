package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.MaintenancePayment
import org.devvikram.societymanagement.maintenance.model.PaymentAllocation
import java.time.LocalDate

// flatIds == null means every flat in the society; an empty set matches nothing. Dates are paid-on days,
// inclusive.
data class PaymentSearch(
    val societyId: Long,
    val flatIds: Set<Long>? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val includeVoid: Boolean = true,
    val page: Int = 0,
    val size: Int = 20,
)

// Reads return payments with society, flat (and its wing), recordedBy and voidedBy loaded; findById and
// findForExport also load the allocations and their bills.
interface MaintenancePaymentRepository {
    // Saves the payment with its allocations.
    fun save(payment: MaintenancePayment): MaintenancePayment
    fun findById(id: Long): MaintenancePayment?
    // Only the payment's society id, without loading the payment (so it can be locked before reading).
    fun findSocietyId(id: Long): Long?
    // Newest payment first.
    fun search(search: PaymentSearch): List<MaintenancePayment>
    // Every payment (void ones too) paid within [from, to], oldest first.
    fun findForExport(societyId: Long, from: LocalDate, to: LocalDate): List<MaintenancePayment>
    // How many payments the society ever recorded, void ones included: the basis of receipt numbers.
    fun countBySocietyId(societyId: Long): Long
    // Allocations to the bill from payments that are not void, with the payment loaded.
    fun activeAllocationsOfBill(billId: Long): List<PaymentAllocation>
}
