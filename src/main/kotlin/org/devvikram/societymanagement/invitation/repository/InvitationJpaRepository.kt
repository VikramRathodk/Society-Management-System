package org.devvikram.societymanagement.invitation.repository

import org.devvikram.societymanagement.invitation.model.Invitation
import org.devvikram.societymanagement.invitation.model.InvitationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InvitationJpaRepository : JpaRepository<Invitation, Long> {
    fun findByTokenHash(tokenHash: String): Invitation?

    @Query(
        "select i from Invitation i where i.society.id = :societyId and i.role.id = :roleId and i.phone = :phone " +
            "and i.flat is null and i.status in :statuses"
    )
    fun findOpenWithoutFlat(
        @Param("societyId") societyId: Long,
        @Param("roleId") roleId: Long,
        @Param("phone") phone: String,
        @Param("statuses") statuses: Collection<InvitationStatus>,
    ): List<Invitation>

    @Query(
        "select i from Invitation i where i.society.id = :societyId and i.role.id = :roleId and i.phone = :phone " +
            "and i.flat.id = :flatId and i.status in :statuses"
    )
    fun findOpenForFlat(
        @Param("societyId") societyId: Long,
        @Param("roleId") roleId: Long,
        @Param("phone") phone: String,
        @Param("flatId") flatId: Long,
        @Param("statuses") statuses: Collection<InvitationStatus>,
    ): List<Invitation>

    fun findByPhoneAndStatusOrderByCreatedAtDesc(phone: String, status: InvitationStatus): List<Invitation>

    @Query(
        "select count(i) from Invitation i where i.flat.id = :flatId and " +
            "(i.status = 'AWAITING_APPROVAL' or (i.status = 'PENDING' and i.expiresAt > :now))"
    )
    fun countOpenForFlat(@Param("flatId") flatId: Long, @Param("now") now: java.time.Instant): Long
}
