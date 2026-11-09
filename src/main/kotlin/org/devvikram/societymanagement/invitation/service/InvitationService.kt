package org.devvikram.societymanagement.invitation.service

import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.model.Role
import org.devvikram.societymanagement.authorization.model.RoleScope
import org.devvikram.societymanagement.authorization.repository.RoleRepository
import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.invitation.model.Invitation
import org.devvikram.societymanagement.invitation.model.InvitationStatus
import org.devvikram.societymanagement.invitation.repository.InvitationRepository
import org.devvikram.societymanagement.invitation.repository.InvitationSearch
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

// Who may invite: anyone who could grant the role there (USER.INVITE.CREATE plus a higher role in that
// society, see Access.canGrant). The same check runs again when the invitation is accepted, so an
// inviter who has since lost their role can't still bring people in through old links.
@Service
class InvitationService(
    private val repository: InvitationRepository,
    private val roleRepository: RoleRepository,
    private val flatRepository: FlatRepository,
    private val societyService: SocietyService,
    private val userService: UserService,
    private val accessService: AccessService,
    private val settings: SocietySettingsService,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {

    @Transactional
    fun create(command: CreateInvitationCommand, actor: Access): IssuedInvitation {
        val now = clock.instant()
        val phone = command.phone.trim()
        val society = societyService.getById(command.societyId)
        val role = roleRepository.findByCode(command.roleCode)
            ?: throw NoSuchElementException("Role ${command.roleCode} not found")
        require(role.scopeLevel == RoleScope.SOCIETY) { "Only society roles can be given by invitation" }
        if (!actor.canGrant(Permissions.USER_INVITE_CREATE, role.code, society.id!!)) {
            throw AccessDeniedException("You can't invite someone as ${role.name} in this society")
        }

        val flat = if (role.requiresFlat) {
            val flatId = requireNotNull(command.flatId) { "${role.name} needs a flat" }
            requireNotNull(command.residentType) { "${role.name} needs a resident type (owner, family or tenant)" }
            val flat = flatRepository.findById(flatId) ?: throw NoSuchElementException("Flat $flatId not found")
            require(flat.wing.society.id == society.id) { "Flat ${flat.flatNumber} does not belong to this society" }
            flat
        } else {
            require(command.flatId == null && command.residentType == null) {
                "A flat and resident type only apply to roles tied to a flat"
            }
            null
        }

        userService.getByPhone(phone)?.let { existing ->
            if (!existing.isActive) throw ConflictException("The account for this phone number is deactivated")
            val holds = userService.getRoles(existing.id!!).any {
                it.role.code == role.code && it.society?.id == society.id && it.flat?.id == flat?.id
            }
            if (holds) throw ConflictException("${existing.name} already has this role here")
        }

        // Re-inviting replaces the open invitation: its link stops working.
        val inviter = userService.getById(actor.userId)
        repository.findOpen(society.id!!, role.id!!, phone, flat?.id).forEach {
            close(it, if (it.statusAt(now) == InvitationStatus.EXPIRED) InvitationStatus.EXPIRED else InvitationStatus.REVOKED, inviter, now)
        }
        repository.flush()

        val token = InvitationTokens.newToken()
        val invitation = repository.save(
            Invitation(
                society = society,
                role = role,
                flat = flat,
                residentType = command.residentType,
                phone = phone,
                name = command.name.trim(),
                tokenHash = InvitationTokens.hash(token),
                invitedBy = inviter,
                createdAt = now,
                expiresAt = expiryFrom(now, society.id!!),
            )
        )
        events.publishEvent(sentEvent(invitation))
        return IssuedInvitation(InvitationView.of(invitation, now), token)
    }

    // Society roles the actor could invite someone to here, by name.
    fun invitableRoles(societyId: Long, actor: Access): List<Role> {
        societyService.getById(societyId)
        return roleRepository.findAll()
            .filter { it.scopeLevel == RoleScope.SOCIETY && actor.canGrant(Permissions.USER_INVITE_CREATE, it.code, societyId) }
            .sortedBy { it.name }
    }

    @Transactional(readOnly = true)
    fun search(search: InvitationSearch): List<InvitationView> =
        repository.search(search).map { InvitationView.of(it, search.now) }

    @Transactional(readOnly = true)
    fun get(id: Long): InvitationView = InvitationView.of(find(id), clock.instant())

    @Transactional(readOnly = true)
    fun preview(token: String): InvitationPreview {
        val invitation = byToken(token)
        return InvitationPreview(
            societyName = invitation.society.name,
            roleCode = invitation.role.code,
            roleName = invitation.role.name,
            flatLabel = flatLabel(invitation),
            residentType = invitation.residentType,
            name = invitation.name,
            maskedPhone = InvitationTokens.maskPhone(invitation.phone),
            invitedByName = invitation.invitedBy.name,
            status = invitation.statusAt(clock.instant()),
            expiresAt = invitation.expiresAt,
            hasAccount = userService.getByPhone(invitation.phone) != null,
        )
    }

    // Open invitations for the logged-in user's phone number, newest first.
    @Transactional(readOnly = true)
    fun mine(userId: Long): List<InvitationView> {
        val now = clock.instant()
        return repository.findPendingForPhone(userService.getById(userId).phone)
            .filter { it.statusAt(now) == InvitationStatus.PENDING }
            .map { InvitationView.of(it, now) }
    }

    // Someone new: the account is created with the invited role, using the password they chose.
    @Transactional
    fun acceptAsNewUser(token: String, name: String?, password: String): InvitationView {
        val now = clock.instant()
        val invitation = requirePending(byToken(token), now)
        if (userService.getByPhone(invitation.phone) != null) {
            throw ConflictException("This phone number already has an account. Log in to accept the invitation.")
        }
        requireInviterStillAllowed(invitation)
        val user = userService.createUser(
            CreateUserCommand(
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: invitation.name,
                phone = invitation.phone,
                rawPassword = password,
                role = roleCommand(invitation),
            )
        )
        return accepted(invitation, user, now)
    }

    // A logged-in user accepting an invitation sent to their own phone number (by link or from their list).
    @Transactional
    fun acceptAsUser(token: String, userId: Long): InvitationView = acceptAs(byToken(token), userId)

    @Transactional
    fun acceptAsUser(id: Long, userId: Long): InvitationView = acceptAs(find(id), userId)

    @Transactional
    fun decline(token: String): InvitationView = declined(byToken(token), null)

    @Transactional
    fun decline(id: Long, userId: Long): InvitationView {
        val invitation = find(id)
        val user = userService.getById(userId)
        requireOwnPhone(invitation, user)
        return declined(invitation, user)
    }

    @Transactional
    fun revoke(id: Long, actor: Access): InvitationView {
        val now = clock.instant()
        val invitation = find(id)
        val sentIt = invitation.invitedBy.id == actor.userId
        if (!sentIt && !actor.canGrant(Permissions.USER_INVITE_CREATE, invitation.role.code, invitation.society.id!!)) {
            throw AccessDeniedException("You can't revoke this invitation")
        }
        if (!invitation.statusAt(now).isOpen) throw ConflictException("This invitation is already ${describe(invitation, now)}")
        close(invitation, InvitationStatus.REVOKED, userService.getById(actor.userId), now)
        return InvitationView.of(invitation, now)
    }

    // A new link (and QR code) for an invitation that hasn't been answered or has expired; the old link stops
    // working and the expiry starts again. Also how to show the QR code again: only the token's hash is kept.
    @Transactional
    fun resend(id: Long, actor: Access): IssuedInvitation {
        val now = clock.instant()
        val invitation = find(id)
        if (!actor.canGrant(Permissions.USER_INVITE_CREATE, invitation.role.code, invitation.society.id!!)) {
            throw AccessDeniedException("You can't resend this invitation")
        }
        when (invitation.status) {
            InvitationStatus.PENDING -> {}
            // Expired ones can be renewed, unless the person has been invited again since.
            InvitationStatus.EXPIRED -> {
                val newer = repository.findOpen(invitation.society.id!!, invitation.role.id!!, invitation.phone, invitation.flat?.id)
                if (newer.isNotEmpty()) throw ConflictException("${invitation.name} has a newer invitation; use that one")
                invitation.status = InvitationStatus.PENDING
                invitation.closedAt = null
                invitation.closedBy = null
            }
            else -> throw ConflictException("This invitation is already ${describe(invitation, now)}; send a new one instead")
        }
        val token = InvitationTokens.newToken()
        invitation.tokenHash = InvitationTokens.hash(token)
        invitation.expiresAt = expiryFrom(now, invitation.society.id!!)
        repository.save(invitation)
        events.publishEvent(sentEvent(invitation))
        return IssuedInvitation(InvitationView.of(invitation, now), token)
    }

    fun societyOf(id: Long): Long = find(id).society.id!!

    private fun acceptAs(invitation: Invitation, userId: Long): InvitationView {
        val now = clock.instant()
        val user = userService.getById(userId)
        requireOwnPhone(invitation, user)
        requirePending(invitation, now)
        requireInviterStillAllowed(invitation)
        val holds = userService.getRoles(user.id!!).any {
            it.role.code == invitation.role.code && it.society?.id == invitation.society.id && it.flat?.id == invitation.flat?.id
        }
        if (holds) throw ConflictException("You already have this role here")
        userService.assignRole(user.id!!, roleCommand(invitation))
        return accepted(invitation, user, now)
    }

    private fun accepted(invitation: Invitation, user: User, now: Instant): InvitationView {
        invitation.acceptedUser = user
        close(invitation, InvitationStatus.ACCEPTED, user, now)
        events.publishEvent(
            InvitationAccepted(
                invitationId = invitation.id!!,
                inviterId = invitation.invitedBy.id!!,
                personName = user.name,
                roleName = invitation.role.name,
                societyName = invitation.society.name,
            )
        )
        return InvitationView.of(invitation, now)
    }

    private fun declined(invitation: Invitation, by: User?): InvitationView {
        val now = clock.instant()
        requirePending(invitation, now)
        close(invitation, InvitationStatus.DECLINED, by, now)
        return InvitationView.of(invitation, now)
    }

    private fun close(invitation: Invitation, status: InvitationStatus, by: User?, now: Instant) {
        invitation.status = status
        invitation.closedAt = now
        invitation.closedBy = by
        repository.save(invitation)
    }

    private fun requirePending(invitation: Invitation, now: Instant): Invitation {
        if (invitation.statusAt(now) != InvitationStatus.PENDING) {
            throw ConflictException("This invitation is ${describe(invitation, now)}")
        }
        return invitation
    }

    private fun requireOwnPhone(invitation: Invitation, user: User) {
        if (invitation.phone != user.phone) throw AccessDeniedException("This invitation is for another phone number")
    }

    private fun requireInviterStillAllowed(invitation: Invitation) {
        val inviter = invitation.invitedBy
        val grants = userService.getRoles(inviter.id!!).map { it.toGrant() }
        val allowed = inviter.isActive &&
            accessService.accessFor(inviter.id!!, grants)
                .canGrant(Permissions.USER_INVITE_CREATE, invitation.role.code, invitation.society.id!!)
        if (!allowed) {
            throw ConflictException("${inviter.name} can no longer give this role. Ask the society for a new invitation.")
        }
    }

    private fun roleCommand(invitation: Invitation) = AssignRoleCommand(
        roleCode = invitation.role.code,
        societyId = invitation.society.id,
        flatId = invitation.flat?.id,
        residentType = invitation.residentType,
    )

    private fun expiryFrom(now: Instant, societyId: Long): Instant =
        now.plus(Duration.ofDays(settings.integer(societyId, SocietySettingKeys.INVITE_EXPIRY_DAYS)))

    private fun sentEvent(invitation: Invitation) = InvitationSent(
        invitationId = invitation.id!!,
        phone = invitation.phone,
        inviterName = invitation.invitedBy.name,
        roleName = invitation.role.name,
        societyName = invitation.society.name,
    )

    private fun describe(invitation: Invitation, now: Instant) = when (invitation.statusAt(now)) {
        InvitationStatus.PENDING -> "waiting for an answer"
        InvitationStatus.AWAITING_APPROVAL -> "waiting for approval"
        InvitationStatus.ACCEPTED -> "accepted"
        InvitationStatus.DECLINED -> "declined"
        InvitationStatus.REJECTED -> "rejected"
        InvitationStatus.REVOKED -> "cancelled"
        InvitationStatus.EXPIRED -> "expired"
    }

    private fun find(id: Long): Invitation =
        repository.findById(id) ?: throw NoSuchElementException("Invitation $id not found")

    private fun byToken(token: String): Invitation =
        repository.findByTokenHash(InvitationTokens.hash(token))
            ?: throw NoSuchElementException("This invitation link is not valid. Ask for a new one.")
}
