package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class IssuedBill(val billId: Long, val flatId: Long, val flatNumber: String, val total: BigDecimal)

// Events carry everything the message needs, so listeners never touch lazy associations.
data class MaintenanceBillsIssued(
    val cycleId: Long,
    val societyId: Long,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val dueDate: LocalDate,
    val bills: List<IssuedBill>,
)

data class MaintenancePaymentRecorded(
    val paymentId: Long,
    val societyId: Long,
    val flatId: Long,
    val flatNumber: String,
    val receiptNumber: String,
    val amount: BigDecimal,
)

data class OverdueFlat(val flatId: Long, val flatNumber: String, val overdue: BigDecimal)

data class MaintenanceDuesReminder(val societyId: Long, val flats: List<OverdueFlat>)

// Runs only after the transaction commits, so nobody hears about something that was rolled back.
// Best-effort: a failure is logged, never surfaced to the person who acted.
@Component
class MaintenanceNotificationListener(
    private val userRoleRepository: UserRoleRepository,
    private val userRepository: UserRepository,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // One message per flat, to every active resident of it.
    @TransactionalEventListener
    fun onIssued(event: MaintenanceBillsIssued) {
        try {
            val billsByFlat = event.bills.associateBy { it.flatId }
            val period = periodLabel(event.periodStart, event.periodEnd)
            val due = event.dueDate.format(DAY)
            for ((flatId, tokens) in tokensByFlat(event.societyId, billsByFlat.keys)) {
                val bill = billsByFlat.getValue(flatId)
                pushNotifier.send(
                    tokens,
                    title = "Maintenance bill",
                    body = "$period bill for flat ${bill.flatNumber}: ${rupees(bill.total)}, due $due",
                    data = mapOf(
                        "type" to "MAINTENANCE_BILL_ISSUED",
                        "billId" to bill.billId.toString(),
                        "cycleId" to event.cycleId.toString(),
                    ),
                )
            }
        } catch (e: Exception) {
            log.warn("Push notification failed for maintenance cycle {}", event.cycleId, e)
        }
    }

    @TransactionalEventListener
    fun onPaymentRecorded(event: MaintenancePaymentRecorded) {
        try {
            val tokens = tokensByFlat(event.societyId, setOf(event.flatId))[event.flatId] ?: return
            pushNotifier.send(
                tokens,
                title = "Payment received",
                body = "${rupees(event.amount)} received for flat ${event.flatNumber} (receipt ${event.receiptNumber})",
                data = mapOf("type" to "MAINTENANCE_PAYMENT_RECORDED", "paymentId" to event.paymentId.toString()),
            )
        } catch (e: Exception) {
            log.warn("Push notification failed for maintenance payment {}", event.paymentId, e)
        }
    }

    @TransactionalEventListener
    fun onDuesReminder(event: MaintenanceDuesReminder) {
        try {
            val byFlat = event.flats.associateBy { it.flatId }
            for ((flatId, tokens) in tokensByFlat(event.societyId, byFlat.keys)) {
                val flat = byFlat.getValue(flatId)
                pushNotifier.send(
                    tokens,
                    title = "Maintenance overdue",
                    body = "Flat ${flat.flatNumber} has ${rupees(flat.overdue)} of maintenance overdue. Please pay soon.",
                    data = mapOf("type" to "MAINTENANCE_DUES_REMINDER", "flatId" to flatId.toString()),
                )
            }
        } catch (e: Exception) {
            log.warn("Push notification failed for maintenance reminders in society {}", event.societyId, e)
        }
    }

    // Device tokens of the active residents (anyone holding a role tied to the flat) of each flat that has any.
    private fun tokensByFlat(societyId: Long, flatIds: Set<Long>): Map<Long, List<String>> {
        val userIdsByFlat = userRoleRepository.findByUserIds(userRoleRepository.findUserIdsBySocietyIds(listOf(societyId)))
            .filter { it.society?.id == societyId && it.flat?.id in flatIds }
            .groupBy({ it.flat!!.id!! }, { it.user.id!! })
        val users = userRepository.findAllById(userIdsByFlat.values.flatten().toSet())
            .filter { it.isActive }
            .associateBy { it.id!! }
        return userIdsByFlat
            .mapValues { (_, userIds) -> userIds.mapNotNull { users[it]?.fcmToken }.distinct() }
            .filterValues { it.isNotEmpty() }
    }

    private fun rupees(amount: BigDecimal): String = NumberFormat.getCurrencyInstance(INDIA).format(amount)

    // "November 2026" for a whole calendar month, otherwise "1 Nov 2026 to 31 Jan 2027".
    private fun periodLabel(start: LocalDate, end: LocalDate): String =
        if (start.dayOfMonth == 1 && end == start.plusMonths(1).minusDays(1)) {
            start.format(MONTH)
        } else {
            "${start.format(DAY)} to ${end.format(DAY)}"
        }

    private companion object {
        val INDIA: Locale = Locale.forLanguageTag("en-IN")
        val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    }
}
