package org.devvikram.societymanagement.policy.repository

import org.devvikram.societymanagement.policy.model.PolicyCategory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

interface PolicyCategoryRepository {
    fun save(category: PolicyCategory): PolicyCategory
    fun findById(id: Long): PolicyCategory?
    // In page order.
    fun findBySociety(societyId: Long): List<PolicyCategory>
    // Adds the names the society doesn't have yet (ignoring case), each at its given position.
    fun insertMissing(societyId: Long, names: List<String>)
}

interface PolicyCategoryJpaRepository : JpaRepository<PolicyCategory, Long> {
    fun findBySocietyIdOrderByPositionAscIdAsc(societyId: Long): List<PolicyCategory>

    // ON CONFLICT: two first reads of the same society at once both try to insert the defaults.
    @Modifying
    @Query(
        value = "INSERT INTO policy_category (society_id, name, position, hidden, created_at) " +
            "VALUES (:societyId, :name, :position, false, now()) ON CONFLICT DO NOTHING",
        nativeQuery = true,
    )
    fun insertIfMissing(societyId: Long, name: String, position: Int)
}

@Repository
class PolicyCategoryRepositoryImpl(private val jpa: PolicyCategoryJpaRepository) : PolicyCategoryRepository {
    override fun save(category: PolicyCategory): PolicyCategory = jpa.save(category)

    override fun findById(id: Long): PolicyCategory? = jpa.findById(id).orElse(null)

    override fun findBySociety(societyId: Long): List<PolicyCategory> = jpa.findBySocietyIdOrderByPositionAscIdAsc(societyId)

    override fun insertMissing(societyId: Long, names: List<String>) =
        names.forEachIndexed { index, name -> jpa.insertIfMissing(societyId, name, index + 1) }
}
