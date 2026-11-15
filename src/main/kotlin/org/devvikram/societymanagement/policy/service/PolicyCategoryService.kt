package org.devvikram.societymanagement.policy.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.policy.model.PolicyCategory
import org.devvikram.societymanagement.policy.repository.PolicyCategoryRepository
import org.devvikram.societymanagement.society.service.SocietyService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// The headings of a society's rules page. Every society starts with DEFAULTS, added the first time its
// categories are read; the secretary renames, reorders, adds or hides them. Never deleted: a hidden one
// keeps its policies and is only left out of the choices for new ones.
@Service
class PolicyCategoryService(
    private val repository: PolicyCategoryRepository,
    private val societyService: SocietyService,
) {
    @Transactional
    fun list(societyId: Long): List<PolicyCategory> {
        societyService.getById(societyId)
        val existing = repository.findBySociety(societyId)
        if (existing.isNotEmpty()) return existing
        repository.insertMissing(societyId, DEFAULTS)
        return repository.findBySociety(societyId)
    }

    @Transactional
    fun create(societyId: Long, name: String): Long {
        val categories = list(societyId)
        val clean = cleanName(name)
        requireUnique(categories, clean, exceptId = null)
        val position = (categories.maxOfOrNull { it.position } ?: 0) + 1
        return repository.save(PolicyCategory(societyId, clean, position)).id!!
    }

    // null leaves a field unchanged.
    @Transactional
    fun update(id: Long, name: String?, position: Int?, hidden: Boolean?) {
        val category = get(id)
        name?.let {
            val clean = cleanName(it)
            requireUnique(repository.findBySociety(category.societyId), clean, exceptId = id)
            category.name = clean
        }
        position?.let {
            require(it in 1..1000) { "Position must be from 1 to 1000" }
            category.position = it
        }
        hidden?.let { category.hidden = it }
        repository.save(category)
    }

    fun get(id: Long): PolicyCategory =
        repository.findById(id) ?: throw NoSuchElementException("Policy category $id not found")

    private fun cleanName(name: String): String {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        require(clean.isNotEmpty()) { "Category name must not be blank" }
        require(clean.length <= 60) { "Category name can be at most 60 characters" }
        return clean
    }

    private fun requireUnique(categories: List<PolicyCategory>, name: String, exceptId: Long?) {
        if (categories.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }) {
            throw ConflictException("There is already a category called $name")
        }
    }

    companion object {
        val DEFAULTS = listOf(
            "By-laws",
            "Parking",
            "Pets",
            "Noise & timings",
            "Renovation & repairs",
            "Move-in / move-out",
            "Amenities",
            "Security & visitors",
            "Waste & cleanliness",
            "Payments & dues",
            "Other",
        )
    }
}
