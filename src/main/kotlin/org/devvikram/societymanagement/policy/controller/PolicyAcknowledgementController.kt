package org.devvikram.societymanagement.policy.controller

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.common.export.Csv
import org.devvikram.societymanagement.policy.dto.AcknowledgementReportResponse
import org.devvikram.societymanagement.policy.dto.AcknowledgementResponse
import org.devvikram.societymanagement.policy.dto.PolicySummaryResponse
import org.devvikram.societymanagement.policy.dto.ReminderResponse
import org.devvikram.societymanagement.policy.service.PolicyAcknowledgementService
import org.devvikram.societymanagement.policy.service.PolicyService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Residents (a flat role holding ACKNOWLEDGEMENT.CREATE) acknowledge; COMMITTEE and above
// (ACKNOWLEDGEMENT.VIEW) see who has, per flat, remind the rest and export the list.
@RestController
class PolicyAcknowledgementController(
    private val acknowledgementService: PolicyAcknowledgementService,
    private val policyService: PolicyService,
    private val accessService: AccessService,
    @Value("\${app.report.zone}") private val zone: ZoneId,
) {
    // The latest published version of the policy (the caller has read it).
    @PostMapping("/api/policy-versions/{versionId}/acknowledge")
    @RequiresPermission(Permissions.POLICY_ACKNOWLEDGEMENT_CREATE)
    fun acknowledge(@PathVariable versionId: Long): AcknowledgementResponse {
        val access = accessService.current()
        access.requireIn(Permissions.POLICY_ACKNOWLEDGEMENT_CREATE, policyService.societyOfVersion(versionId))
        val acknowledgement = acknowledgementService.acknowledge(versionId, access)
        val counted = policyService.getVersion(acknowledgement.versionId)
        return AcknowledgementResponse(counted.policyId, counted.number, acknowledgement.acknowledgedAt)
    }

    // The caller's own list: published policies still waiting for their acknowledgement.
    @GetMapping("/api/societies/{societyId}/policies/pending-acknowledgements")
    @RequiresPermission(Permissions.POLICY_ACKNOWLEDGEMENT_CREATE)
    fun pending(@PathVariable societyId: Long): List<PolicySummaryResponse> {
        val access = accessService.current()
        access.requireIn(Permissions.POLICY_ACKNOWLEDGEMENT_CREATE, societyId)
        val pending = acknowledgementService.pendingFor(societyId, access)
        val state = acknowledgementService.acknowledgedAt(pending, access, societyId)
        return pending.map { PolicySummaryResponse.from(it, emptyMap(), state) }
    }

    // state=all (default), pending or done filters the flats; the counts are for all of them.
    @GetMapping("/api/policies/{policyId}/acknowledgements")
    @RequiresPermission(Permissions.POLICY_ACKNOWLEDGEMENT_VIEW)
    fun report(@PathVariable policyId: Long, @RequestParam(defaultValue = "all") state: String): AcknowledgementReportResponse {
        requireView(policyId)
        val report = acknowledgementService.report(policyId)
        val flats = when (state.trim().lowercase()) {
            "all" -> report.flats
            "pending" -> report.flats.filter { !it.done }
            "done" -> report.flats.filter { it.done }
            else -> throw IllegalArgumentException("state must be one of all, pending, done")
        }
        return AcknowledgementReportResponse.from(report, flats)
    }

    @PostMapping("/api/policies/{policyId}/acknowledgements/remind")
    @RequiresPermission(Permissions.POLICY_ACKNOWLEDGEMENT_VIEW)
    fun remind(@PathVariable policyId: Long): ReminderResponse {
        val userId = requireView(policyId)
        val result = acknowledgementService.remind(policyId, userId)
        return ReminderResponse(result.reminded, result.skipped)
    }

    // One row per person, so a flat appears once for each member.
    @GetMapping("/api/policies/{policyId}/acknowledgements.csv")
    @RequiresPermission(Permissions.POLICY_ACKNOWLEDGEMENT_VIEW)
    fun csv(@PathVariable policyId: Long): ResponseEntity<ByteArray> {
        requireView(policyId)
        val report = acknowledgementService.report(policyId)
        val rows = report.flats.flatMap { flat ->
            flat.members.map {
                listOf(
                    flat.wing,
                    flat.flatNumber,
                    if (flat.done) "Done" else "Pending",
                    it.member.name,
                    it.member.residentType?.name?.lowercase() ?: "owner",
                    time(it.acknowledgedAt),
                    time(flat.lastRemindedAt),
                )
            }
        }
        val content = Csv.write(listOf("Wing", "Flat", "Flat status", "Name", "Resident", "Acknowledged at", "Last reminded"), rows)
        return Csv.download("policy-$policyId-acknowledgements.csv", content)
    }

    private fun requireView(policyId: Long): Long {
        val access = accessService.current()
        access.requireIn(Permissions.POLICY_ACKNOWLEDGEMENT_VIEW, policyService.get(policyId).societyId)
        return access.userId
    }

    private fun time(at: Instant?): String = at?.let { FORMAT.format(it.atZone(zone)) } ?: ""

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
