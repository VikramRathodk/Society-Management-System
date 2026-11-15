package org.devvikram.societymanagement.policy.repository

import jakarta.persistence.LockModeType
import org.devvikram.societymanagement.policy.model.Policy
import org.devvikram.societymanagement.policy.model.PolicyVersion
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

interface PolicyRepository {
    fun save(policy: Policy): Policy
    fun findById(id: Long): Policy?
    // For a change to the policy or its versions: one change at a time per policy.
    fun lock(id: Long): Policy?
    fun findBySociety(societyId: Long): List<Policy>
    fun delete(policy: Policy)
}

interface PolicyJpaRepository : JpaRepository<Policy, Long> {
    fun findBySocietyIdOrderByIdAsc(societyId: Long): List<Policy>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Policy p WHERE p.id = :id")
    fun findLocked(id: Long): Policy?
}

@Repository
class PolicyRepositoryImpl(private val jpa: PolicyJpaRepository) : PolicyRepository {
    override fun save(policy: Policy): Policy = jpa.save(policy)

    override fun findById(id: Long): Policy? = jpa.findById(id).orElse(null)

    override fun lock(id: Long): Policy? = jpa.findLocked(id)

    override fun findBySociety(societyId: Long): List<Policy> = jpa.findBySocietyIdOrderByIdAsc(societyId)

    override fun delete(policy: Policy) = jpa.delete(policy)
}

interface PolicyVersionRepository {
    fun save(version: PolicyVersion): PolicyVersion
    fun findById(id: Long): PolicyVersion?
    // Oldest first.
    fun findByPolicy(policyId: Long): List<PolicyVersion>
    fun findByPolicies(policyIds: Collection<Long>): List<PolicyVersion>
    fun findByPolicyAndNumber(policyId: Long, number: Int): PolicyVersion?
    fun delete(version: PolicyVersion)
}

interface PolicyVersionJpaRepository : JpaRepository<PolicyVersion, Long> {
    fun findByPolicyIdOrderByNumberAsc(policyId: Long): List<PolicyVersion>
    fun findByPolicyIdInOrderByNumberAsc(policyIds: Collection<Long>): List<PolicyVersion>
    fun findByPolicyIdAndNumber(policyId: Long, number: Int): PolicyVersion?
}

@Repository
class PolicyVersionRepositoryImpl(private val jpa: PolicyVersionJpaRepository) : PolicyVersionRepository {
    override fun save(version: PolicyVersion): PolicyVersion = jpa.saveAndFlush(version)

    override fun findById(id: Long): PolicyVersion? = jpa.findById(id).orElse(null)

    override fun findByPolicy(policyId: Long): List<PolicyVersion> = jpa.findByPolicyIdOrderByNumberAsc(policyId)

    override fun findByPolicies(policyIds: Collection<Long>): List<PolicyVersion> =
        if (policyIds.isEmpty()) emptyList() else jpa.findByPolicyIdInOrderByNumberAsc(policyIds)

    override fun findByPolicyAndNumber(policyId: Long, number: Int): PolicyVersion? = jpa.findByPolicyIdAndNumber(policyId, number)

    override fun delete(version: PolicyVersion) = jpa.delete(version)
}
