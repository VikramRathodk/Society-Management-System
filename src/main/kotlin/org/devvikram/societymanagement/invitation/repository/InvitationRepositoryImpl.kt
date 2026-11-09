package org.devvikram.societymanagement.invitation.repository

import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.invitation.model.Invitation
import org.devvikram.societymanagement.invitation.model.InvitationStatus
import org.springframework.stereotype.Repository

@Repository
class InvitationRepositoryImpl(
    private val jpaRepository: InvitationJpaRepository,
    private val entityManager: EntityManager,
) : InvitationRepository {
    override fun save(invitation: Invitation): Invitation = jpaRepository.save(invitation)

    override fun flush() = jpaRepository.flush()

    override fun findById(id: Long): Invitation? = jpaRepository.findById(id).orElse(null)

    override fun findByTokenHash(tokenHash: String): Invitation? = jpaRepository.findByTokenHash(tokenHash)

    override fun findOpen(societyId: Long, roleId: Long, phone: String, flatId: Long?): List<Invitation> =
        if (flatId == null) jpaRepository.findOpenWithoutFlat(societyId, roleId, phone, OPEN)
        else jpaRepository.findOpenForFlat(societyId, roleId, phone, flatId, OPEN)

    override fun findPendingForPhone(phone: String): List<Invitation> =
        jpaRepository.findByPhoneAndStatusOrderByCreatedAtDesc(phone, InvitationStatus.PENDING)

    override fun search(search: InvitationSearch): List<Invitation> {
        val where = mutableListOf("i.society.id = :societyId")
        when (search.status) {
            null -> {}
            InvitationStatus.PENDING -> where += "i.status = 'PENDING' and i.expiresAt > :now"
            InvitationStatus.EXPIRED -> where += "(i.status = 'EXPIRED' or (i.status = 'PENDING' and i.expiresAt <= :now))"
            else -> where += "i.status = :status"
        }
        val query = entityManager.createQuery(
            "select i from Invitation i join fetch i.society join fetch i.role join fetch i.invitedBy " +
                "left join fetch i.flat f left join fetch f.wing " +
                "where ${where.joinToString(" and ")} order by i.createdAt desc, i.id desc",
            Invitation::class.java,
        )
        query.setParameter("societyId", search.societyId)
        when (search.status) {
            null -> {}
            InvitationStatus.PENDING, InvitationStatus.EXPIRED -> query.setParameter("now", search.now)
            else -> query.setParameter("status", search.status)
        }
        return query.setFirstResult(search.page * search.size).setMaxResults(search.size).resultList
    }

    private companion object {
        val OPEN = listOf(InvitationStatus.PENDING, InvitationStatus.AWAITING_APPROVAL)
    }
}
