package org.devvikram.societymanagement.onboarding.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.onboarding.dto.ApplicationStatusResponse
import org.devvikram.societymanagement.onboarding.dto.RejectApplicationRequest
import org.devvikram.societymanagement.onboarding.dto.SelfSignupResponse
import org.devvikram.societymanagement.onboarding.dto.SocietyApplicationRequest
import org.devvikram.societymanagement.onboarding.dto.SocietyApplicationResponse
import org.devvikram.societymanagement.onboarding.model.ApplicationStatus
import org.devvikram.societymanagement.onboarding.service.SocietyApplicationService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Public, under /api/society-applications/public: whether registration is open, register, check a
// registration. A signed-in caller who registers applies with their own account. Platform (SUPER_ADMIN):
// the list, approve and reject.
@RestController
class SocietyApplicationController(
    private val service: SocietyApplicationService,
    private val accessService: AccessService,
) {
    @GetMapping("/api/society-applications/public/enabled")
    fun enabled() = SelfSignupResponse(service.selfSignupEnabled)

    @PostMapping("/api/society-applications/public")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@Valid @RequestBody request: SocietyApplicationRequest, http: HttpServletRequest): ApplicationStatusResponse =
        ApplicationStatusResponse.from(
            service.submit(request.toCommand(), accessService.currentOrNull()?.userId, http.remoteAddr)
        )

    @GetMapping("/api/society-applications/public/status")
    fun status(@RequestParam number: Long, @RequestParam phone: String): ApplicationStatusResponse =
        ApplicationStatusResponse.from(service.status(number, phone))

    // Pending first (longest waiting at the top), then the decided ones, newest first.
    @GetMapping("/api/society-applications")
    @RequiresPermission(Permissions.SOCIETY_APPLICATION_VIEW)
    fun list(@RequestParam(required = false) status: ApplicationStatus?): List<SocietyApplicationResponse> {
        accessService.current().requireOnPlatform(Permissions.SOCIETY_APPLICATION_VIEW)
        val (pending, decided) = service.list(status).partition { it.status == ApplicationStatus.PENDING }
        return (pending.sortedBy { it.createdAt } + decided).map(SocietyApplicationResponse::from)
    }

    @GetMapping("/api/society-applications/{id}")
    @RequiresPermission(Permissions.SOCIETY_APPLICATION_VIEW)
    fun get(@PathVariable id: Long): SocietyApplicationResponse {
        accessService.current().requireOnPlatform(Permissions.SOCIETY_APPLICATION_VIEW)
        return SocietyApplicationResponse.from(service.get(id))
    }

    @PostMapping("/api/society-applications/{id}/approve")
    @RequiresPermission(Permissions.SOCIETY_APPLICATION_UPDATE)
    fun approve(@PathVariable id: Long): SocietyApplicationResponse {
        val access = accessService.current()
        access.requireOnPlatform(Permissions.SOCIETY_APPLICATION_UPDATE)
        return SocietyApplicationResponse.from(service.approve(id, access.userId))
    }

    @PostMapping("/api/society-applications/{id}/reject")
    @RequiresPermission(Permissions.SOCIETY_APPLICATION_UPDATE)
    fun reject(@PathVariable id: Long, @Valid @RequestBody request: RejectApplicationRequest): SocietyApplicationResponse {
        val access = accessService.current()
        access.requireOnPlatform(Permissions.SOCIETY_APPLICATION_UPDATE)
        return SocietyApplicationResponse.from(service.reject(id, request.reason, access.userId))
    }
}
