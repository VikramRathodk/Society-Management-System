package org.devvikram.societymanagement.policy.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.policy.dto.CreatePolicyRequest
import org.devvikram.societymanagement.policy.dto.PolicyResponse
import org.devvikram.societymanagement.policy.dto.PolicySummaryResponse
import org.devvikram.societymanagement.policy.dto.ReasonRequest
import org.devvikram.societymanagement.policy.dto.SaveDraftRequest
import org.devvikram.societymanagement.policy.dto.UpdatePolicyRequest
import org.devvikram.societymanagement.policy.dto.VersionResponse
import org.devvikram.societymanagement.policy.dto.peopleOf
import org.devvikram.societymanagement.policy.service.CreatePolicyCommand
import org.devvikram.societymanagement.policy.service.DraftCommand
import org.devvikram.societymanagement.policy.service.PolicyAcknowledgementService
import org.devvikram.societymanagement.policy.service.PolicyActor
import org.devvikram.societymanagement.policy.service.PolicyFilter
import org.devvikram.societymanagement.policy.service.PolicyService
import org.devvikram.societymanagement.policy.service.UpdatePolicyCommand
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

// Who may do what with policies (all within the policy's society):
//  - everyone with a role in the society reads published policies and their history (RULE.VIEW);
//  - COMMITTEE and above draft (RULE.CREATE), edit, submit and withdraw their own drafts (RULE.UPDATE)
//    and see everyone's drafts and what waits for approval;
//  - CHAIRMAN and above (RULE.APPROVE) approve or send back, edit anyone's draft, archive and restore,
//    and change a published policy's title or category. They approve their own draft only when the
//    society allows it. With approval off, a draft's author publishes it directly.
@RestController
class PolicyController(
    private val policyService: PolicyService,
    private val acknowledgementService: PolicyAcknowledgementService,
    private val accessService: AccessService,
) {
    // ---- Policies ----

    // The rules page (filter=published, the default) or archived policies; committee queues
    // filter=drafts and filter=awaiting-approval. Ordered by category, then title.
    @GetMapping("/api/societies/{societyId}/policies")
    @RequiresPermission(Permissions.POLICY_RULE_VIEW)
    fun list(
        @PathVariable societyId: Long,
        @RequestParam(defaultValue = "published") filter: String,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) q: String?,
    ): List<PolicySummaryResponse> {
        val actor = actor(Permissions.POLICY_RULE_VIEW, societyId)
        val details = policyService.list(societyId, parseFilter(filter), categoryId, q, actor)
        val names = policyService.names(details.flatMap { d -> d.open?.let { listOf(it.createdBy) }.orEmpty() })
        val acknowledged = acknowledgementService.acknowledgedAt(details, accessService.current(), societyId)
        return details.map { PolicySummaryResponse.from(it, names, acknowledged) }
    }

    @PostMapping("/api/societies/{societyId}/policies")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.POLICY_RULE_CREATE)
    fun create(@PathVariable societyId: Long, @Valid @RequestBody request: CreatePolicyRequest): PolicyResponse {
        val actor = actor(Permissions.POLICY_RULE_CREATE, societyId)
        val id = policyService.create(
            societyId,
            CreatePolicyCommand(
                categoryId = request.categoryId!!,
                title = request.title!!,
                requiresAcknowledgement = request.requiresAcknowledgement,
                body = request.body.orEmpty(),
                changeSummary = request.changeSummary,
                effectiveFrom = request.effectiveFrom,
            ),
            actor,
        )
        return view(id, actor)
    }

    @GetMapping("/api/policies/{id}")
    @RequiresPermission(Permissions.POLICY_RULE_VIEW)
    fun get(@PathVariable id: Long): PolicyResponse =
        view(id, actor(Permissions.POLICY_RULE_VIEW, policyService.get(id).societyId))

    @PatchMapping("/api/policies/{id}")
    @RequiresPermission(Permissions.POLICY_RULE_UPDATE)
    fun update(@PathVariable id: Long, @RequestBody request: UpdatePolicyRequest): PolicyResponse {
        val actor = actorForPolicy(Permissions.POLICY_RULE_UPDATE, id)
        policyService.update(id, UpdatePolicyCommand(request.categoryId, request.title, request.requiresAcknowledgement), actor)
        return view(id, actor)
    }

    // A policy that was never published.
    @DeleteMapping("/api/policies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.POLICY_RULE_UPDATE)
    fun delete(@PathVariable id: Long) {
        policyService.delete(id, actorForPolicy(Permissions.POLICY_RULE_UPDATE, id))
    }

    @PostMapping("/api/policies/{id}/archive")
    @RequiresPermission(Permissions.POLICY_RULE_APPROVE)
    fun archive(@PathVariable id: Long): PolicyResponse {
        val actor = actorForPolicy(Permissions.POLICY_RULE_APPROVE, id)
        policyService.archive(id, actor)
        return view(id, actor)
    }

    @PostMapping("/api/policies/{id}/restore")
    @RequiresPermission(Permissions.POLICY_RULE_APPROVE)
    fun restore(@PathVariable id: Long): PolicyResponse {
        val actor = actorForPolicy(Permissions.POLICY_RULE_APPROVE, id)
        policyService.restore(id, actor)
        return view(id, actor)
    }

    // ---- Versions ----

    @GetMapping("/api/policies/{id}/versions/{number}")
    @RequiresPermission(Permissions.POLICY_RULE_VIEW)
    fun version(@PathVariable id: Long, @PathVariable number: Int): VersionResponse {
        val details = policyService.details(id, actorForPolicy(Permissions.POLICY_RULE_VIEW, id))
        val version = details.versions.firstOrNull { it.number == number }
            ?: throw NoSuchElementException("Version $number of policy $id not found")
        return VersionResponse.from(version, version === details.inForce, policyService.names(peopleOf(details)))
    }

    // A new draft of a published policy, from its current text.
    @PostMapping("/api/policies/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.POLICY_RULE_CREATE)
    fun startVersion(@PathVariable id: Long): PolicyResponse {
        val actor = actorForPolicy(Permissions.POLICY_RULE_CREATE, id)
        policyService.startVersion(id, actor)
        return view(id, actor)
    }

    @PutMapping("/api/policy-versions/{versionId}")
    @RequiresPermission(Permissions.POLICY_RULE_UPDATE)
    fun saveDraft(@PathVariable versionId: Long, @RequestBody request: SaveDraftRequest): PolicyResponse =
        onVersion(versionId, Permissions.POLICY_RULE_UPDATE) { actor ->
            policyService.saveDraft(
                versionId,
                DraftCommand(request.body.orEmpty(), request.changeSummary, request.effectiveFrom, request.materialChange),
                actor,
            )
        }

    // Drops a draft; for a policy that was never published, the policy goes with it (204 either way).
    @DeleteMapping("/api/policy-versions/{versionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.POLICY_RULE_UPDATE)
    fun deleteDraft(@PathVariable versionId: Long) {
        policyService.deleteDraft(versionId, actorForVersion(Permissions.POLICY_RULE_UPDATE, versionId))
    }

    @PutMapping("/api/policy-versions/{versionId}/attachment", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @RequiresPermission(Permissions.POLICY_RULE_UPDATE)
    fun setAttachment(@PathVariable versionId: Long, @RequestPart("file") file: MultipartFile): PolicyResponse =
        onVersion(versionId, Permissions.POLICY_RULE_UPDATE) { actor ->
            policyService.setAttachment(versionId, file.bytes, file.originalFilename, actor)
        }

    @DeleteMapping("/api/policy-versions/{versionId}/attachment")
    @RequiresPermission(Permissions.POLICY_RULE_UPDATE)
    fun removeAttachment(@PathVariable versionId: Long): PolicyResponse =
        onVersion(versionId, Permissions.POLICY_RULE_UPDATE) { actor -> policyService.removeAttachment(versionId, actor) }

    @GetMapping("/api/policy-versions/{versionId}/attachment")
    @RequiresPermission(Permissions.POLICY_RULE_VIEW)
    fun attachment(@PathVariable versionId: Long): ResponseEntity<ByteArray> {
        val (version, file) = policyService.attachment(versionId, actorForVersion(Permissions.POLICY_RULE_VIEW, versionId))
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline()
                    .filename(version.attachmentName ?: "policy-attachment-$versionId", Charsets.UTF_8).build().toString(),
            )
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(file.data)
    }

    // ---- Workflow ----

    @PostMapping("/api/policy-versions/{versionId}/submit")
    @RequiresPermission(Permissions.POLICY_RULE_UPDATE)
    fun submit(@PathVariable versionId: Long): PolicyResponse =
        onVersion(versionId, Permissions.POLICY_RULE_UPDATE) { actor -> policyService.submit(versionId, actor) }

    @PostMapping("/api/policy-versions/{versionId}/withdraw")
    @RequiresPermission(Permissions.POLICY_RULE_UPDATE)
    fun withdraw(@PathVariable versionId: Long): PolicyResponse =
        onVersion(versionId, Permissions.POLICY_RULE_UPDATE) { actor -> policyService.withdraw(versionId, actor) }

    @PostMapping("/api/policy-versions/{versionId}/approve")
    @RequiresPermission(Permissions.POLICY_RULE_APPROVE)
    fun approve(@PathVariable versionId: Long): PolicyResponse =
        onVersion(versionId, Permissions.POLICY_RULE_APPROVE) { actor -> policyService.approve(versionId, actor) }

    @PostMapping("/api/policy-versions/{versionId}/send-back")
    @RequiresPermission(Permissions.POLICY_RULE_APPROVE)
    fun sendBack(@PathVariable versionId: Long, @Valid @RequestBody request: ReasonRequest): PolicyResponse =
        onVersion(versionId, Permissions.POLICY_RULE_APPROVE) { actor -> policyService.sendBack(versionId, request.reason!!, actor) }

    // Only when the society has turned approval off.
    @PostMapping("/api/policy-versions/{versionId}/publish")
    @RequiresPermission(Permissions.POLICY_RULE_UPDATE)
    fun publish(@PathVariable versionId: Long): PolicyResponse =
        onVersion(versionId, Permissions.POLICY_RULE_UPDATE) { actor -> policyService.publishDirectly(versionId, actor) }

    // ---- Helpers ----

    private fun view(policyId: Long, actor: PolicyActor): PolicyResponse {
        val details = policyService.details(policyId, actor)
        val acknowledged = acknowledgementService.acknowledgedAt(listOf(details), accessService.current(), details.policy.societyId)
        return PolicyResponse.from(details, policyService.names(peopleOf(details)), acknowledged)
    }

    private fun onVersion(versionId: Long, permission: String, action: (PolicyActor) -> Unit): PolicyResponse {
        val actor = actorForVersion(permission, versionId)
        val policyId = policyService.getVersion(versionId).policyId
        action(actor)
        return view(policyId, actor)
    }

    private fun actor(permission: String, societyId: Long): PolicyActor {
        val access = accessService.current()
        access.requireIn(permission, societyId)
        return actorOf(access, societyId)
    }

    private fun actorForPolicy(permission: String, policyId: Long) = actor(permission, policyService.get(policyId).societyId)

    private fun actorForVersion(permission: String, versionId: Long) = actor(permission, policyService.societyOfVersion(versionId))

    private fun actorOf(access: Access, societyId: Long) = PolicyActor(
        userId = access.userId,
        canDraft = access.canSocietyWide(Permissions.POLICY_RULE_CREATE, societyId),
        canApprove = access.canSocietyWide(Permissions.POLICY_RULE_APPROVE, societyId),
    )

    private fun parseFilter(raw: String): PolicyFilter =
        PolicyFilter.entries.firstOrNull { it.name == raw.trim().uppercase().replace('-', '_') }
            ?: throw IllegalArgumentException("filter must be one of published, archived, drafts, awaiting-approval")
}
