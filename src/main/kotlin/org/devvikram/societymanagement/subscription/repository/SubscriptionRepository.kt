package org.devvikram.societymanagement.subscription.repository

import jakarta.persistence.LockModeType
import org.devvikram.societymanagement.subscription.model.Plan
import org.devvikram.societymanagement.subscription.model.Subscription
import org.devvikram.societymanagement.subscription.model.SubscriptionEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

interface PlanRepository {
    fun save(plan: Plan): Plan
    fun findById(id: Long): Plan?
    fun findByCode(code: String): Plan?
    // In display order.
    fun findAll(): List<Plan>
}

interface PlanJpaRepository : JpaRepository<Plan, Long> {
    fun findByCode(code: String): Plan?
    fun findAllByOrderBySortOrderAscIdAsc(): List<Plan>
}

@Repository
class PlanRepositoryImpl(private val jpa: PlanJpaRepository) : PlanRepository {
    override fun save(plan: Plan): Plan = jpa.saveAndFlush(plan)

    override fun findById(id: Long): Plan? = jpa.findById(id).orElse(null)

    override fun findByCode(code: String): Plan? = jpa.findByCode(code)

    override fun findAll(): List<Plan> = jpa.findAllByOrderBySortOrderAscIdAsc()
}

interface SubscriptionRepository {
    fun save(subscription: Subscription): Subscription
    fun findBySociety(societyId: Long): Subscription?
    fun lockBySociety(societyId: Long): Subscription?
    fun findAll(): List<Subscription>
}

interface SubscriptionJpaRepository : JpaRepository<Subscription, Long> {
    fun findBySocietyId(societyId: Long): Subscription?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.societyId = :societyId")
    fun findLockedBySociety(societyId: Long): Subscription?
}

@Repository
class SubscriptionRepositoryImpl(private val jpa: SubscriptionJpaRepository) : SubscriptionRepository {
    override fun save(subscription: Subscription): Subscription = jpa.saveAndFlush(subscription)

    override fun findBySociety(societyId: Long): Subscription? = jpa.findBySocietyId(societyId)

    override fun lockBySociety(societyId: Long): Subscription? = jpa.findLockedBySociety(societyId)

    override fun findAll(): List<Subscription> = jpa.findAll()
}

interface SubscriptionEventRepository {
    fun save(event: SubscriptionEvent): SubscriptionEvent
    // Oldest first.
    fun findBySubscription(subscriptionId: Long): List<SubscriptionEvent>
}

interface SubscriptionEventJpaRepository : JpaRepository<SubscriptionEvent, Long> {
    fun findBySubscriptionIdOrderByCreatedAtAscIdAsc(subscriptionId: Long): List<SubscriptionEvent>
}

@Repository
class SubscriptionEventRepositoryImpl(private val jpa: SubscriptionEventJpaRepository) : SubscriptionEventRepository {
    override fun save(event: SubscriptionEvent): SubscriptionEvent = jpa.save(event)

    override fun findBySubscription(subscriptionId: Long): List<SubscriptionEvent> =
        jpa.findBySubscriptionIdOrderByCreatedAtAscIdAsc(subscriptionId)
}
