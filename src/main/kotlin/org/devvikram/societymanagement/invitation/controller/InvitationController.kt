package org.devvikram.societymanagement.invitation.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.authorization.dto.AssignableRoleResponse
import org.devvikram.societymanagement.invitation.dto.AcceptInvitationRequest
import org.devvikram.societymanagement.invitation.dto.CreateInvitationRequest
import org.devvikram.societymanagement.invitation.dto.InvitationPreviewResponse
import org.devvikram.societymanagement.invitation.dto.InvitationResponse
import org.devvikram.societymanagement.invitation.dto.IssuedInvitationResponse
import org.devvikram.societymanagement.invitation.model.InvitationStatus
import org.devvikram.societymanagement.invitation.repository.InvitationSearch
import org.devvikram.societymanagement.invitation.service.InvitationService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

// Society side: COMMITTEE and above invite roles below their own and manage the society's invitations.
// Person side: /api/invitations/token/** is public (whoever holds the link); /mine and accept/decline by id
// are for a logged-in user whose phone number the invitation was sent to.
@RestController
class InvitationController(
    private val invitationService: InvitationService,
    private val accessService: AccessService,
    private val clock: Clock,
    @Value("\${app.web-base-url}") private val webBaseUrl: String,
) {

    @PostMapping("/api/societies/{societyId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.USER_INVITE_CREATE)
    fun create(@PathVariable societyId: Long, @Valid @RequestBody request: CreateInvitationRequest): IssuedInvitationResponse =
        IssuedInvitationResponse.from(
            invitationService.create(request.toCommand(societyId), accessService.current()),
            webBaseUrl,
        )

    // The roles the caller may invite people to in this society (drives the invite dialog's role picker).
    @GetMapping("/api/societies/{societyId}/invitations/roles")
    @RequiresPermission(Permissions.USER_INVITE_CREATE)
    fun invitableRoles(@PathVariable societyId: Long): List<AssignableRoleResponse> =
        invitationService.invitableRoles(societyId, accessService.current())
            .map { AssignableRoleResponse(it.code, it.name, it.scopeLevel, it.requiresFlat) }

    // Newest first; status=PENDING / EXPIRED reflect expiry even before the row is updated.
    @GetMapping("/api/societies/{societyId}/invitations")
    @RequiresPermission(Permissions.USER_INVITE_VIEW)
    fun list(
        @PathVariable societyId: Long,
        @RequestParam(required = false) status: InvitationStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<InvitationResponse> {
        requireSocietyWide(Permissions.USER_INVITE_VIEW, societyId)
        val search = InvitationSearch(
            societyId = societyId,
            status = status,
            now = clock.instant(),
            page = page.coerceAtLeast(0),
            size = size.coerceIn(1, 100),
        )
        return invitationService.search(search).map(InvitationResponse::from)
    }

    @GetMapping("/api/invitations/{id}")
    @RequiresPermission(Permissions.USER_INVITE_VIEW)
    fun get(@PathVariable id: Long): InvitationResponse {
        requireSocietyWide(Permissions.USER_INVITE_VIEW, invitationService.societyOf(id))
        return InvitationResponse.from(invitationService.get(id))
    }

    @PostMapping("/api/invitations/{id}/resend")
    @RequiresPermission(Permissions.USER_INVITE_CREATE)
    fun resend(@PathVariable id: Long): IssuedInvitationResponse =
        IssuedInvitationResponse.from(invitationService.resend(id, accessService.current()), webBaseUrl)

    @PostMapping("/api/invitations/{id}/revoke")
    @RequiresPermission(Permissions.USER_INVITE_CREATE)
    fun revoke(@PathVariable id: Long): InvitationResponse =
        InvitationResponse.from(invitationService.revoke(id, accessService.current()))

    // Open invitations sent to the logged-in user's phone number.
    @GetMapping("/api/invitations/mine")
    fun mine(): List<InvitationResponse> =
        invitationService.mine(accessService.current().userId).map(InvitationResponse::from)

    @PostMapping("/api/invitations/{id}/accept")
    fun acceptMine(@PathVariable id: Long): InvitationResponse =
        InvitationResponse.from(invitationService.acceptAsUser(id, accessService.current().userId))

    @PostMapping("/api/invitations/{id}/decline")
    fun declineMine(@PathVariable id: Long): InvitationResponse =
        InvitationResponse.from(invitationService.decline(id, accessService.current().userId))

    @GetMapping("/api/invitations/token/{token}")
    fun preview(@PathVariable token: String): InvitationPreviewResponse =
        InvitationPreviewResponse.from(invitationService.preview(token))

    // Logged in: adds the role to that account (its phone must match). Otherwise creates the account,
    // which needs a password; the person then logs in with their phone number and that password.
    @PostMapping("/api/invitations/token/{token}/accept")
    fun accept(
        @PathVariable token: String,
        @Valid @RequestBody(required = false) request: AcceptInvitationRequest?,
    ): InvitationResponse {
        val caller = accessService.currentOrNull()
        val accepted = if (caller != null) {
            invitationService.acceptAsUser(token, caller.userId)
        } else {
            val password = requireNotNull(request?.password) { "Choose a password of 8 to 72 characters" }
            invitationService.acceptAsNewUser(token, request?.name, password)
        }
        return InvitationResponse.from(accepted)
    }

    @PostMapping("/api/invitations/token/{token}/decline")
    fun decline(@PathVariable token: String): InvitationResponse =
        InvitationResponse.from(invitationService.decline(token))

    private fun requireSocietyWide(permission: String, societyId: Long) {
        if (!accessService.current().canSocietyWide(permission, societyId)) {
            throw AccessDeniedException("Missing permission: $permission for this society")
        }
    }
}
