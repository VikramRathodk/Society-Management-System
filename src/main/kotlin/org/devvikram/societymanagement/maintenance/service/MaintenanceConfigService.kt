package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.model.BillingFrequency
import org.devvikram.societymanagement.maintenance.model.ChargeCalcType
import org.devvikram.societymanagement.maintenance.model.ChargeHead
import org.devvikram.societymanagement.maintenance.model.MaintenanceSettings
import org.devvikram.societymanagement.maintenance.repository.ChargeHeadRepository
import org.devvikram.societymanagement.maintenance.repository.MaintenanceSettingsRepository
import org.devvikram.societymanagement.society.repository.SocietyRepository
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

data class UpdateSettingsCommand(
    val billingFrequency: BillingFrequency,
    val dueDays: Int,
    val roundToRupee: Boolean,
)

data class AddChargeHeadCommand(
    val name: String,
    val calcType: ChargeCalcType,
    val amount: BigDecimal,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
)

// null leaves a field unchanged.
data class UpdateChargeHeadCommand(
    val name: String? = null,
    val calcType: ChargeCalcType? = null,
    val amount: BigDecimal? = null,
    val isActive: Boolean? = null,
    val sortOrder: Int? = null,
)

// A society's billing configuration. Changes apply to cycles issued afterwards; issued bills keep their own
// copy of the charges. *Who* may change it is decided by the caller (MaintenanceConfigController).
@Service
class MaintenanceConfigService(
    private val settingsRepository: MaintenanceSettingsRepository,
    private val chargeHeadRepository: ChargeHeadRepository,
    private val societyRepository: SocietyRepository,
    private val userRepository: UserRepository,
) {
    // The saved settings, or unsaved defaults for a society that never changed them.
    fun settingsOf(societyId: Long): MaintenanceSettings {
        requireSociety(societyId)
        return settingsRepository.findBySocietyId(societyId) ?: MaintenanceSettings(societyId)
    }

    @Transactional
    fun updateSettings(societyId: Long, command: UpdateSettingsCommand, editorId: Long) {
        require(command.dueDays in 0..365) { "Due days must be between 0 and 365" }
        val settings = settingsOf(societyId)
        settings.billingFrequency = command.billingFrequency
        settings.dueDays = command.dueDays
        settings.roundToRupee = command.roundToRupee
        settings.updatedAt = Instant.now()
        settings.updatedBy = userRepository.findById(editorId) ?: throw NoSuchElementException("User $editorId not found")
        settingsRepository.save(settings)
    }

    fun chargeHeads(societyId: Long): List<ChargeHead> {
        requireSociety(societyId)
        return chargeHeadRepository.findBySocietyId(societyId)
    }

    fun getChargeHead(id: Long): ChargeHead =
        chargeHeadRepository.findById(id) ?: throw NoSuchElementException("Charge head $id not found")

    @Transactional
    fun addChargeHead(societyId: Long, command: AddChargeHeadCommand): Long {
        val society = societyRepository.findById(societyId) ?: throw NoSuchElementException("Society $societyId not found")
        val name = cleanName(command.name)
        requireUniqueName(societyId, name, exceptId = null)
        requireValidAmount(command.amount)
        val head = chargeHeadRepository.save(
            ChargeHead(
                society = society,
                name = name,
                calcType = command.calcType,
                amount = command.amount,
                isActive = command.isActive,
                sortOrder = command.sortOrder,
            )
        )
        return head.id!!
    }

    @Transactional
    fun updateChargeHead(id: Long, command: UpdateChargeHeadCommand) {
        val head = getChargeHead(id)
        command.name?.let {
            val name = cleanName(it)
            requireUniqueName(head.society.id!!, name, exceptId = head.id)
            head.name = name
        }
        command.calcType?.let { head.calcType = it }
        command.amount?.let {
            requireValidAmount(it)
            head.amount = it
        }
        command.isActive?.let { head.isActive = it }
        command.sortOrder?.let { head.sortOrder = it }
        chargeHeadRepository.save(head)
    }

    // Safe at any time: issued bills copied the head's name and amount.
    @Transactional
    fun deleteChargeHead(id: Long) {
        chargeHeadRepository.delete(getChargeHead(id))
    }

    private fun requireSociety(societyId: Long) {
        societyRepository.findById(societyId) ?: throw NoSuchElementException("Society $societyId not found")
    }

    private fun cleanName(name: String): String {
        require(name.isNotBlank()) { "Name must not be blank" }
        return name.trim()
    }

    private fun requireUniqueName(societyId: Long, name: String, exceptId: Long?) {
        require(chargeHeadRepository.findBySocietyId(societyId).none { it.id != exceptId && it.name.equals(name, ignoreCase = true) }) {
            "A charge head named $name already exists"
        }
    }

    private fun requireValidAmount(amount: BigDecimal) {
        require(amount.signum() >= 0) { "Amount must not be negative" }
        require(amount.scale() <= 2) { "Amount can have at most 2 decimal places" }
    }
}
