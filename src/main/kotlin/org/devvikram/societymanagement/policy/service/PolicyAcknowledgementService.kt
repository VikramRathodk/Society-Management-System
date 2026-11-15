package org.devvikram.societymanagement.policy.service

import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.policy.model.PolicyAcknowledgement
import org.devvikram.societymanagement.policy.model.PolicyReminder
import org.devvikram.societymanagement.policy.model.PolicyStatus
import org.devvikram.societymanagement.policy.repository.PolicyAcknowledgementRepository
import org.devvikram.societymanagement.policy.repository.PolicyReminderRepository
import org.devvikram.societymanagement.policy.repository.PolicyRepository
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.user.model.ResidentType
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

// A person who acknowledges for a flat: an active member of the flat through a role that may acknowledge.
data class FlatMember(val userId: Long, val name: String, val flatId: Long, val residentType: ResidentType?)

data class MemberAcknowledgement(val member: FlatMember, val acknowledgedAt: Instant?)

// One flat in the report. A flat is done when the person the rules bind has acknowledged: a tenant of a
// rented flat, otherwise its owner (a member without a type counts as the owner); a flat with neither
// (family only) is done when anyone has.
data class FlatAcknowledgement(
    val flatId: Long,
    val wing: String,
    val flatNumber: String,
    val members: List<MemberAcknowledgement>,
    val lastRemindedAt: Instant?,
) {
    val done: Boolean
        get() {
            val tenants = members.filter { it.member.residentType == ResidentType.TENANT }
            val owners = members.filter { it.member.residentType == null || it.member.residentType == ResidentType.OWNER }
            val deciding = tenants.ifEmpty { owners }.ifEmpty { members }
            return deciding.any { it.acknowledgedAt != null }
        }
}

data class AcknowledgementReport(
    val details: PolicyDetails,
    val flats: List<FlatAcknowledgement>,
) {
    val doneFlats: Int get() = flats.count { it.done }
    val pendingFlats: Int get() = flats.size - doneFlats
    // Distinct people: an owner of two flats counts once.
    val people: Int get() = flats.flatMap { f -> f.members.filter { it.acknowledgedAt != null }.map { it.member.userId } }.toSet().size
}

data class ReminderResult(val reminded: Int, val skipped: Int)

// Residents acknowledging the policies that ask for it, the committee's per-flat follow-up, reminders.
@Service
class PolicyAcknowledgementService(
    private val policyService: PolicyService,
    private val policyRepository: PolicyRepository,
    private val acknowledgementRepository: PolicyAcknowledgementRepository,
    private val reminderRepository: PolicyReminderRepository,
    private val userRoleRepository: UserRoleRepository,
    private val flatRepository: FlatRepository,
    private val accessService: AccessService,
    private val settings: SocietySettingsService,
    private val events: ApplicationEventPublisher,
) {
    // Only the latest published text can be acknowledged, and only through a flat role; acknowledging
    // twice keeps the first time.
    @Transactional
    fun acknowledge(versionId: Long, access: Access): PolicyAcknowledgement {
        val policyId = policyService.getVersion(versionId).policyId
        policyRepository.lock(policyId)
        val details = policyService.details(policyId, readerOf(access))
        val basis = askingVersion(details)
        if (details.current?.id != versionId) {
            throw ConflictException("A newer version of this policy has been published; read it first")
        }
        val flats = ownFlats(access, details.policy.societyId)
        if (flats.isEmpty()) throw AccessDeniedException("Only residents acknowledge policies")
        acknowledgementRepository.find(basis.id!!, access.userId)?.let { return it }
        return acknowledgementRepository.save(PolicyAcknowledgement(basis.id!!, access.userId, flats.min()))
    }

    // When the caller acknowledged each policy's current text (absent: not yet). Only policies that ask
    // for it; empty for a caller without a flat in the society.
    @Transactional(readOnly = true)
    fun acknowledgedAt(details: List<PolicyDetails>, access: Access, societyId: Long): Map<Long, Instant?>? {
        if (ownFlats(access, societyId).isEmpty()) return null
        val asking = details.mapNotNull { d -> d.acknowledgementVersion?.let { d.policy.id!! to it.id!! } }.toMap()
        val given = acknowledgementRepository.findByUser(access.userId, asking.values).associateBy { it.versionId }
        return asking.mapValues { (_, versionId) -> given[versionId]?.acknowledgedAt }
    }

    // Published policies the caller still has to acknowledge.
    @Transactional(readOnly = true)
    fun pendingFor(societyId: Long, access: Access): List<PolicyDetails> {
        val published = policyService.list(societyId, PolicyFilter.PUBLISHED, null, null, readerOf(access))
        val state = acknowledgedAt(published, access, societyId) ?: return emptyList()
        return published.filter { it.policy.id in state && state[it.policy.id] == null }
    }

    // In a transaction: members and flats are read through lazy associations.
    @Transactional(readOnly = true)
    fun report(policyId: Long): AcknowledgementReport {
        val details = policyService.details(policyId, PolicyActor(0, canDraft = false, canApprove = false))
        val basis = askingVersion(details)
        val acknowledged = acknowledgementRepository.findByVersion(basis.id!!).associateBy { it.userId }
        val reminded = reminderRepository.findByVersion(basis.id!!).groupBy { it.flatId }.mapValues { (_, r) -> r.maxOf { it.sentAt } }
        val flats = flatRepository.findBySocietyId(details.policy.societyId).associateBy { it.id!! }
        val rows = members(details.policy.societyId).groupBy { it.flatId }
            .mapNotNull { (flatId, members) ->
                val flat = flats[flatId] ?: return@mapNotNull null
                FlatAcknowledgement(
                    flatId = flatId,
                    wing = flat.wing.name,
                    flatNumber = flat.flatNumber,
                    members = members.sortedBy { it.name.lowercase() }
                        .map { MemberAcknowledgement(it, acknowledged[it.userId]?.acknowledgedAt) },
                    lastRemindedAt = reminded[flatId],
                )
            }
            .sortedWith(compareBy({ it.wing.lowercase() }, { it.flatNumber.length }, { it.flatNumber }))
        return AcknowledgementReport(details, rows)
    }

    // Pushes the members of pending flats who haven't acknowledged, skipping flats reminded within the
    // society's `policyReminderDays`.
    @Transactional
    fun remind(policyId: Long, userId: Long): ReminderResult {
        policyRepository.lock(policyId)
        val report = report(policyId)
        val basis = askingVersion(report.details)
        val days = settings.integer(report.details.policy.societyId, SocietySettingKeys.POLICY_REMINDER_DAYS)
        val now = Instant.now()
        val cutoff = now.minus(Duration.ofDays(days))
        val (due, recent) = report.flats.filter { !it.done }.partition { it.lastRemindedAt == null || it.lastRemindedAt.isBefore(cutoff) }
        due.forEach { reminderRepository.save(PolicyReminder(basis.id!!, it.flatId, userId, now)) }
        val userIds = due.flatMap { f -> f.members.filter { it.acknowledgedAt == null }.map { it.member.userId } }.toSet()
        if (userIds.isNotEmpty()) {
            events.publishEvent(PolicyReminderSent(policyId, report.details.policy.title, userIds))
        }
        return ReminderResult(reminded = due.size, skipped = recent.size)
    }

    // The policy asks for acknowledgement and is in force or about to be.
    private fun askingVersion(details: PolicyDetails) = when {
        details.policy.status != PolicyStatus.ACTIVE -> throw ConflictException("Only a published policy is acknowledged")
        else -> details.acknowledgementVersion ?: throw ConflictException("This policy doesn't ask residents to acknowledge it")
    }

    // Flats of the society the caller belongs to through a role that may acknowledge.
    private fun ownFlats(access: Access, societyId: Long): Set<Long> {
        val allowed = access.flatsWith(Permissions.POLICY_ACKNOWLEDGEMENT_CREATE)
        return access.grants.filter { it.societyId == societyId && it.flatId != null && it.flatId in allowed }.mapNotNull { it.flatId }.toSet()
    }

    private fun members(societyId: Long): List<FlatMember> {
        val roles = userRoleRepository.findByUserIds(userRoleRepository.findUserIdsBySocietyIds(listOf(societyId)))
        return roles.groupBy { it.user.id!! }.flatMap { (userId, userRoles) ->
            val user = userRoles.first().user
            if (!user.isActive) return@flatMap emptyList()
            val access = accessService.accessFor(userId, userRoles.map { it.toGrant() })
            val allowed = access.flatsWith(Permissions.POLICY_ACKNOWLEDGEMENT_CREATE)
            userRoles.filter { it.society?.id == societyId && it.flat?.id != null && it.flat!!.id in allowed }
                .distinctBy { it.flat!!.id }
                .map { FlatMember(userId, user.name, it.flat!!.id!!, it.residentType) }
        }
    }

    // Reading a policy as an ordinary member: published versions only.
    private fun readerOf(access: Access) = PolicyActor(access.userId, canDraft = false, canApprove = false)
}
