package org.devvikram.societymanagement.maintenance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.maintenance.dto.FlatCreditResponse
import org.devvikram.societymanagement.maintenance.dto.RefundReasonRequest
import org.devvikram.societymanagement.maintenance.dto.RefundRequest
import org.devvikram.societymanagement.maintenance.dto.RefundResponse
import org.devvikram.societymanagement.maintenance.model.MaintenanceRefund
import org.devvikram.societymanagement.maintenance.model.RefundStatus
import org.devvikram.societymanagement.maintenance.service.MaintenanceAudience
import org.devvikram.societymanagement.maintenance.service.RefundService
import org.devvikram.societymanagement.user.repository.UserRepository
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
import java.time.LocalDate
import java.time.ZoneId

// Refunds of unused maintenance credit.
//  - SECRETARY and above (or a treasurer role) record and void them (MAINTENANCE.REFUND.CREATE).
//  - COMMITTEE and above approve or reject waiting ones (MAINTENANCE.REFUND.APPROVE), never their own.
//  - Who sees them follows payments: residents their own flat's, society-wide roles all.
@RestController
class MaintenanceRefundController(
    private val refundService: RefundService,
    private val accessService: AccessService,
    private val audience: MaintenanceAudience,
    private val userRepository: UserRepository,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {
    @PostMapping("/api/societies/{societyId}/maintenance/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.MAINTENANCE_REFUND_CREATE)
    fun create(@PathVariable societyId: Long, @Valid @RequestBody request: RefundRequest): RefundResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_REFUND_CREATE, societyId)
        return view(refundService.create(societyId, request.toCommand(), access.userId, LocalDate.now(reportZone)))
    }

    // A flat's unused credit (what could be refunded).
    @GetMapping("/api/societies/{societyId}/maintenance/flats/{flatId}/credit")
    @RequiresPermission(Permissions.MAINTENANCE_PAYMENT_VIEW)
    fun credit(@PathVariable societyId: Long, @PathVariable flatId: Long): FlatCreditResponse {
        if (!audience.canSee(accessService.current(), Permissions.MAINTENANCE_PAYMENT_VIEW, societyId, flatId)) {
            throw AccessDeniedException("You cannot see this flat's credit")
        }
        return FlatCreditResponse(flatId, refundService.creditOfFlat(societyId, flatId))
    }

    // Newest first; residents get their own flat(s) only.
    @GetMapping("/api/maintenance/refunds")
    @RequiresPermission(Permissions.MAINTENANCE_PAYMENT_VIEW)
    fun refunds(
        @RequestParam societyId: Long,
        @RequestParam(required = false) flatId: Long?,
        @RequestParam(required = false) status: Set<RefundStatus>?,
    ): List<RefundResponse> {
        val access = accessService.current()
        access.requireIn(Permissions.MAINTENANCE_PAYMENT_VIEW, societyId)
        val flatIds = if (access.canSocietyWide(Permissions.MAINTENANCE_PAYMENT_VIEW, societyId)) {
            flatId?.let { setOf(it) }
        } else {
            val own = audience.ownFlats(access, Permissions.MAINTENANCE_PAYMENT_VIEW, societyId)
            if (flatId != null && flatId !in own) throw AccessDeniedException("You can only see refunds of your own flat")
            flatId?.let { setOf(it) } ?: own
        }
        return views(refundService.search(societyId, flatIds, status?.takeIf { it.isNotEmpty() }))
    }

    @GetMapping("/api/maintenance/refunds/{id}")
    @RequiresPermission(Permissions.MAINTENANCE_PAYMENT_VIEW)
    fun refund(@PathVariable id: Long): RefundResponse {
        val refund = refundService.get(id)
        if (!audience.canSee(accessService.current(), Permissions.MAINTENANCE_PAYMENT_VIEW, refund.society.id!!, refund.flat.id!!)) {
            throw AccessDeniedException("You cannot see this refund")
        }
        return views(listOf(refund)).single()
    }

    @PostMapping("/api/maintenance/refunds/{id}/approve")
    @RequiresPermission(Permissions.MAINTENANCE_REFUND_APPROVE)
    fun approve(@PathVariable id: Long): RefundResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_REFUND_APPROVE, refundService.get(id).society.id!!)
        refundService.approve(id, access.userId)
        return view(id)
    }

    @PostMapping("/api/maintenance/refunds/{id}/reject")
    @RequiresPermission(Permissions.MAINTENANCE_REFUND_APPROVE)
    fun reject(@PathVariable id: Long, @Valid @RequestBody request: RefundReasonRequest): RefundResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_REFUND_APPROVE, refundService.get(id).society.id!!)
        refundService.reject(id, access.userId, request.reason ?: "")
        return view(id)
    }

    @PostMapping("/api/maintenance/refunds/{id}/withdraw")
    @RequiresPermission(Permissions.MAINTENANCE_REFUND_CREATE)
    fun withdraw(@PathVariable id: Long): RefundResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_REFUND_CREATE, refundService.get(id).society.id!!)
        refundService.withdraw(id, access.userId)
        return view(id)
    }

    @PostMapping("/api/maintenance/refunds/{id}/void")
    @RequiresPermission(Permissions.MAINTENANCE_REFUND_CREATE)
    fun void(@PathVariable id: Long, @Valid @RequestBody request: RefundReasonRequest): RefundResponse {
        val access = requireSocietyWide(Permissions.MAINTENANCE_REFUND_CREATE, refundService.get(id).society.id!!)
        refundService.void(id, access.userId, request.reason ?: "")
        return view(id)
    }

    private fun view(id: Long): RefundResponse = views(listOf(refundService.get(id))).single()

    private fun views(refunds: List<MaintenanceRefund>): List<RefundResponse> {
        val ids = refunds.flatMap { listOfNotNull(it.createdBy, it.decidedBy, it.voidedBy) }.toSet()
        val names = userRepository.findAllById(ids).associate { it.id!! to it.name }
        return refunds.map { RefundResponse.from(it, names) }
    }

    private fun requireSocietyWide(permission: String, societyId: Long): Access = accessService.current().also {
        if (!it.canSocietyWide(permission, societyId)) {
            throw AccessDeniedException("Missing permission: $permission for this society")
        }
    }
}
