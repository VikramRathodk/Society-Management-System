package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.repository.FlatDues
import org.devvikram.societymanagement.maintenance.repository.MaintenanceBillRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

// How many flats were reminded and how much they owe past due.
data class ReminderResult(val flatCount: Int, val totalOverdue: BigDecimal)

// Dues and defaulters. A bill counts as overdue once the society's grace days have passed after its due
// date, the same rule late fees use.
@Service
class MaintenanceReportService(
    private val configService: MaintenanceConfigService,
    private val billRepository: MaintenanceBillRepository,
    private val events: ApplicationEventPublisher,
) {
    // Every flat that owes something; flatIds (when given) narrows it, overdueOnly keeps the defaulters.
    fun dues(societyId: Long, today: LocalDate, flatIds: Set<Long>? = null, overdueOnly: Boolean = false): List<FlatDues> {
        val graceDays = configService.settingsOf(societyId).graceDays
        // overdue ⇔ today > due + grace ⇔ due < today - grace
        return billRepository.dues(societyId, today.minusDays(graceDays.toLong()))
            .filter { flatIds == null || it.flatId in flatIds }
            .filter { !overdueOnly || it.overdue.signum() > 0 }
    }

    // Pushes a reminder to the residents of each defaulting flat (all of them, or only `flatIds`).
    // Transactional only so the push goes out through the after-commit listener like every other one.
    @Transactional(readOnly = true)
    fun remindDefaulters(societyId: Long, today: LocalDate, flatIds: Set<Long>? = null): ReminderResult {
        val defaulters = dues(societyId, today, flatIds, overdueOnly = true)
        if (defaulters.isNotEmpty()) {
            events.publishEvent(
                MaintenanceDuesReminder(societyId, defaulters.map { OverdueFlat(it.flatId, it.flatNumber, it.overdue) })
            )
        }
        return ReminderResult(defaulters.size, defaulters.fold(BigDecimal.ZERO.setScale(2)) { sum, it -> sum + it.overdue })
    }
}
