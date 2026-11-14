package org.devvikram.societymanagement.onboarding.service

import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.repository.WingRepository
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class PlannedFlat(val number: String, val exists: Boolean)

data class PlannedWing(val name: String, val exists: Boolean, val flats: List<PlannedFlat>) {
    val newFlats get() = flats.count { !it.exists }
    val existingFlats get() = flats.count { it.exists }
}

// Bulk wings and flats for the setup wizard. Wings are matched by name (ignoring case) and flats by number
// within the wing: whatever already exists is skipped, never duplicated, so generating twice is harmless.
@Service
class StructureService(
    private val societyService: SocietyService,
    private val wingRepository: WingRepository,
    private val flatRepository: FlatRepository,
    private val wingService: WingService,
) {
    @Transactional(readOnly = true)
    fun preview(societyId: Long, patterns: List<WingPattern>): List<PlannedWing> {
        societyService.getById(societyId)
        val plan = StructurePattern.plan(patterns)
        val wings = wingRepository.findBySocietyId(societyId)
        return plan.map { (name, numbers) ->
            val wing = wings.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
            val existing = wing?.let { w -> flatRepository.findByWingId(w.id!!).map { it.flatNumber }.toSet() }.orEmpty()
            PlannedWing(wing?.name ?: name, wing != null, numbers.map { PlannedFlat(it, it in existing) })
        }
    }

    // Returns the plan as it was before creating, so `exists = false` marks what was just made.
    @Transactional
    fun generate(societyId: Long, patterns: List<WingPattern>): List<PlannedWing> {
        val planned = preview(societyId, patterns)
        val wings = wingRepository.findBySocietyId(societyId)
        planned.forEach { plannedWing ->
            val wing = wings.firstOrNull { it.name.trim().equals(plannedWing.name, ignoreCase = true) }
                ?: wingService.addWing(societyId, plannedWing.name)
            plannedWing.flats.filterNot { it.exists }.forEach { flatRepository.save(Flat(wing = wing, flatNumber = it.number)) }
        }
        return planned
    }
}
