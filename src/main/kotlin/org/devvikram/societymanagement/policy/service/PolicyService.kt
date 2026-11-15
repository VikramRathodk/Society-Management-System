package org.devvikram.societymanagement.policy.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.common.storage.DocumentFormat
import org.devvikram.societymanagement.common.storage.Photo
import org.devvikram.societymanagement.common.storage.PhotoStorage
import org.devvikram.societymanagement.policy.model.Policy
import org.devvikram.societymanagement.policy.model.PolicyCategory
import org.devvikram.societymanagement.policy.model.PolicyStatus
import org.devvikram.societymanagement.policy.model.PolicyVersion
import org.devvikram.societymanagement.policy.model.VersionStatus
import org.devvikram.societymanagement.policy.repository.PolicyRepository
import org.devvikram.societymanagement.policy.repository.PolicyVersionRepository
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Who is acting and what they hold in the policy's society. The controller checks the permission the
// endpoint needs; the service applies the rules that depend on the record (own draft, self-approval).
data class PolicyActor(
    val userId: Long,
    // POLICY.RULE.CREATE: sees drafts and versions waiting for approval.
    val canDraft: Boolean,
    // POLICY.RULE.APPROVE.
    val canApprove: Boolean,
)

data class CreatePolicyCommand(
    val categoryId: Long,
    val title: String,
    val requiresAcknowledgement: Boolean = false,
    val body: String = "",
    val changeSummary: String? = null,
    val effectiveFrom: LocalDate? = null,
)

// null leaves a field unchanged.
data class UpdatePolicyCommand(
    val categoryId: Long? = null,
    val title: String? = null,
    val requiresAcknowledgement: Boolean? = null,
)

// The whole editable text of a draft (replaced together).
data class DraftCommand(
    val body: String,
    val changeSummary: String? = null,
    val effectiveFrom: LocalDate? = null,
    val materialChange: Boolean = true,
)

enum class PolicyFilter { PUBLISHED, ARCHIVED, DRAFTS, AWAITING_APPROVAL }

// A policy with the versions the viewer may see (oldest first) and what is in force today.
data class PolicyDetails(
    val policy: Policy,
    val category: PolicyCategory,
    val versions: List<PolicyVersion>,
    val today: LocalDate,
) {
    // The latest published text (it may take effect later).
    val current: PolicyVersion? = versions.lastOrNull { it.status.published }

    // The published text in force today: the latest one whose effective date has come.
    val inForce: PolicyVersion? = versions.lastOrNull { it.status.published && !it.effectiveFrom!!.isAfter(today) }

    val open: PolicyVersion? = versions.firstOrNull { it.status.open }

    // The version acknowledgements are recorded against: the latest published one marked as a material
    // change (the first always is), so a minor correction keeps the acknowledgements already given.
    // Null when the policy doesn't ask for acknowledgement or isn't published.
    val acknowledgementVersion: PolicyVersion? =
        if (!policy.requiresAcknowledgement) null
        else current?.let { cur -> versions.last { it.status.published && it.number <= cur.number && (it.materialChange || it.number == 1) } }
}

// Policies, their versions and the approval workflow. A version is drafted, submitted for approval and
// approved (or, with approval off in the society, published by its author); publishing supersedes the
// earlier text, which stays readable. A published version is never changed.
@Service
class PolicyService(
    private val policyRepository: PolicyRepository,
    private val versionRepository: PolicyVersionRepository,
    private val categoryService: PolicyCategoryService,
    private val societyService: SocietyService,
    private val settings: SocietySettingsService,
    private val userRepository: UserRepository,
    private val storage: PhotoStorage,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    @Value("\${app.report.zone}") private val zone: ZoneId,
) {
    fun today(): LocalDate = LocalDate.now(clock.withZone(zone))

    // ---- Policies ----

    @Transactional
    fun create(societyId: Long, command: CreatePolicyCommand, actor: PolicyActor): Long {
        societyService.getById(societyId)
        val category = usableCategory(societyId, command.categoryId)
        val policy = policyRepository.save(
            Policy(societyId, category.id!!, cleanTitle(command.title), command.requiresAcknowledgement, actor.userId)
        )
        versionRepository.save(
            PolicyVersion(
                policyId = policy.id!!,
                number = 1,
                body = cleanBody(command.body),
                changeSummary = cleanSummary(command.changeSummary),
                effectiveFrom = command.effectiveFrom,
                materialChange = true,
                createdBy = actor.userId,
            )
        )
        return policy.id!!
    }

    // Before the first publication, the author or an approver; after it, approvers only (the title and
    // category are what residents see).
    @Transactional
    fun update(policyId: Long, command: UpdatePolicyCommand, actor: PolicyActor) {
        val policy = lock(policyId)
        if (policy.status != PolicyStatus.DRAFT || policy.createdBy != actor.userId) requireApprover(actor)
        command.categoryId?.takeIf { it != policy.categoryId }?.let { policy.categoryId = usableCategory(policy.societyId, it).id!! }
        command.title?.let { policy.title = cleanTitle(it) }
        command.requiresAcknowledgement?.let { policy.requiresAcknowledgement = it }
        policy.updatedAt = Instant.now()
        policy.updatedBy = actor.userId
        policyRepository.save(policy)
    }

    // Only a policy that was never published; its author or an approver.
    @Transactional
    fun delete(policyId: Long, actor: PolicyActor) {
        val policy = lock(policyId)
        if (policy.status != PolicyStatus.DRAFT) {
            throw ConflictException("A published policy can't be deleted; archive it instead")
        }
        if (policy.createdBy != actor.userId) requireApprover(actor)
        val keys = versionRepository.findByPolicy(policyId).mapNotNull { it.attachmentKey }
        policyRepository.delete(policy)
        deleteFiles(keys)
    }

    @Transactional
    fun archive(policyId: Long, actor: PolicyActor) {
        requireApprover(actor)
        val policy = lock(policyId)
        if (policy.status != PolicyStatus.ACTIVE) throw ConflictException("Only a published policy can be archived")
        policy.status = PolicyStatus.ARCHIVED
        policy.archivedAt = Instant.now()
        policy.archivedBy = actor.userId
        policyRepository.save(policy)
    }

    @Transactional
    fun restore(policyId: Long, actor: PolicyActor) {
        requireApprover(actor)
        val policy = lock(policyId)
        if (policy.status != PolicyStatus.ARCHIVED) throw ConflictException("The policy is not archived")
        policy.status = PolicyStatus.ACTIVE
        policy.archivedAt = null
        policy.archivedBy = null
        policyRepository.save(policy)
    }

    // ---- Versions ----

    // A new draft of a published policy, starting from the latest published text and attachment.
    @Transactional
    fun startVersion(policyId: Long, actor: PolicyActor): Long {
        val policy = lock(policyId)
        when (policy.status) {
            PolicyStatus.DRAFT -> throw ConflictException("The policy isn't published yet; edit its draft")
            PolicyStatus.ARCHIVED -> throw ConflictException("Restore the policy before changing it")
            PolicyStatus.ACTIVE -> Unit
        }
        val versions = versionRepository.findByPolicy(policyId)
        if (versions.any { it.status.open }) throw ConflictException("The policy already has a change in progress")
        val current = versions.last { it.status.published }
        val draft = PolicyVersion(
            policyId = policyId,
            number = versions.maxOf { it.number } + 1,
            body = current.body,
            changeSummary = null,
            effectiveFrom = null,
            materialChange = true,
            createdBy = actor.userId,
        )
        current.attachmentKey?.let { key ->
            storage.load(key)?.let { file ->
                draft.attachmentKey = storage.save(file.data, file.contentType)
                draft.attachmentName = current.attachmentName
                draft.attachmentType = current.attachmentType
                draft.attachmentSize = current.attachmentSize
            }
        }
        return versionRepository.save(draft).id!!
    }

    // The draft's author or an approver, while it is a draft (a submitted one is withdrawn first).
    @Transactional
    fun saveDraft(versionId: Long, command: DraftCommand, actor: PolicyActor) {
        val (_, version) = lockVersion(versionId)
        requireEditable(version, actor)
        version.body = cleanBody(command.body)
        version.changeSummary = cleanSummary(command.changeSummary)
        version.effectiveFrom = command.effectiveFrom
        version.materialChange = version.number == 1 || command.materialChange
        version.updatedAt = Instant.now()
        versionRepository.save(version)
    }

    @Transactional
    fun setAttachment(versionId: Long, data: ByteArray, filename: String?, actor: PolicyActor) {
        require(data.isNotEmpty()) { "The file is empty" }
        require(data.size <= DocumentFormat.MAX_BYTES) { "Files can be at most 5 MB" }
        val contentType = DocumentFormat.detect(data) ?: throw IllegalArgumentException("Attach a PDF, JPEG, PNG or WebP file")
        val (_, version) = lockVersion(versionId)
        requireEditable(version, actor)
        val old = version.attachmentKey
        version.attachmentKey = storage.save(data, contentType)
        version.attachmentName = filename?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.take(255)?.ifEmpty { null }
        version.attachmentType = contentType
        version.attachmentSize = data.size
        version.updatedAt = Instant.now()
        versionRepository.save(version)
        deleteFiles(listOfNotNull(old))
    }

    @Transactional
    fun removeAttachment(versionId: Long, actor: PolicyActor) {
        val (_, version) = lockVersion(versionId)
        requireEditable(version, actor)
        val old = version.attachmentKey ?: return
        version.attachmentKey = null
        version.attachmentName = null
        version.attachmentType = null
        version.attachmentSize = null
        version.updatedAt = Instant.now()
        versionRepository.save(version)
        deleteFiles(listOf(old))
    }

    // Drops a draft. The first version's draft is the whole policy, so dropping it deletes the policy.
    @Transactional
    fun deleteDraft(versionId: Long, actor: PolicyActor) {
        val (policy, version) = lockVersion(versionId)
        requireEditable(version, actor)
        if (version.number == 1) {
            delete(policy.id!!, actor)
            return
        }
        versionRepository.delete(version)
        deleteFiles(listOfNotNull(version.attachmentKey))
    }

    // ---- Workflow ----

    @Transactional
    fun submit(versionId: Long, actor: PolicyActor) {
        val (policy, version) = lockVersion(versionId)
        requireAuthor(version, actor, "Only the author submits a draft")
        if (version.status != VersionStatus.DRAFT) throw ConflictException("Only a draft can be submitted")
        if (!approvalRequired(policy.societyId)) {
            throw ConflictException("Policies don't need approval in this society; publish it directly")
        }
        requireNotArchived(policy)
        checkPublishable(policy, version)
        version.status = VersionStatus.PENDING
        version.submittedAt = Instant.now()
        versionRepository.save(version)
        events.publishEvent(
            PolicySubmitted(policy.id!!, policy.societyId, policy.title, version.number, actor.userId, nameOf(actor.userId))
        )
    }

    @Transactional
    fun withdraw(versionId: Long, actor: PolicyActor) {
        val (_, version) = lockVersion(versionId)
        requireAuthor(version, actor, "Only the author withdraws a submitted draft")
        if (version.status != VersionStatus.PENDING) throw ConflictException("The version isn't waiting for approval")
        version.status = VersionStatus.DRAFT
        version.submittedAt = null
        versionRepository.save(version)
    }

    @Transactional
    fun sendBack(versionId: Long, reason: String, actor: PolicyActor) {
        requireApprover(actor)
        val clean = reason.trim()
        require(clean.isNotEmpty()) { "Say why it is sent back" }
        require(clean.length <= 500) { "The reason can be at most 500 characters" }
        val (policy, version) = lockVersion(versionId)
        if (version.status != VersionStatus.PENDING) throw ConflictException("The version isn't waiting for approval")
        version.status = VersionStatus.DRAFT
        version.submittedAt = null
        version.reviewedAt = Instant.now()
        version.reviewedBy = actor.userId
        version.reviewReason = clean
        versionRepository.save(version)
        events.publishEvent(PolicySentBack(policy.id!!, policy.title, version.number, version.createdBy, clean))
    }

    @Transactional
    fun approve(versionId: Long, actor: PolicyActor) {
        requireApprover(actor)
        val (policy, version) = lockVersion(versionId)
        if (version.status != VersionStatus.PENDING) throw ConflictException("The version isn't waiting for approval")
        if (version.createdBy == actor.userId &&
            !settings.boolean(policy.societyId, SocietySettingKeys.POLICY_SELF_APPROVAL_ALLOWED)
        ) {
            throw AccessDeniedException("Another approver must approve a policy you drafted")
        }
        version.reviewedAt = Instant.now()
        version.reviewedBy = actor.userId
        version.reviewReason = null
        publish(policy, version, actor)
        if (version.createdBy != actor.userId) {
            events.publishEvent(PolicyApproved(policy.id!!, policy.title, version.number, version.createdBy))
        }
    }

    // Without approval: the author or an approver publishes a draft (or one submitted before approval was
    // turned off) straight away.
    @Transactional
    fun publishDirectly(versionId: Long, actor: PolicyActor) {
        val (policy, version) = lockVersion(versionId)
        if (!version.status.open) throw ConflictException("The version is already published")
        if (approvalRequired(policy.societyId)) {
            throw ConflictException("Policies need approval in this society; submit it for approval")
        }
        if (version.createdBy != actor.userId) requireApprover(actor)
        publish(policy, version, actor)
    }

    private fun publish(policy: Policy, version: PolicyVersion, actor: PolicyActor) {
        requireNotArchived(policy)
        checkPublishable(policy, version)
        val today = today()
        val earlier = versionRepository.findByPolicy(policy.id!!).filter { it.status == VersionStatus.PUBLISHED }
        earlier.forEach {
            it.status = VersionStatus.SUPERSEDED
            versionRepository.save(it)
        }
        version.effectiveFrom = version.effectiveFrom ?: today
        version.status = VersionStatus.PUBLISHED
        version.publishedAt = Instant.now()
        version.publishedBy = actor.userId
        versionRepository.save(version)
        if (policy.status == PolicyStatus.DRAFT) policy.status = PolicyStatus.ACTIVE
        policy.updatedAt = Instant.now()
        policy.updatedBy = actor.userId
        policyRepository.save(policy)
        events.publishEvent(
            PolicyPublished(
                policyId = policy.id!!,
                societyId = policy.societyId,
                title = policy.title,
                number = version.number,
                effectiveFrom = version.effectiveFrom!!,
                upcoming = version.effectiveFrom!!.isAfter(today),
                // The author hears "approved" instead; the publisher knows.
                exceptUserIds = setOf(actor.userId, version.createdBy),
            )
        )
    }

    // Text present, and the effective date not before the text it replaces.
    private fun checkPublishable(policy: Policy, version: PolicyVersion) {
        require(version.body.isNotBlank()) { "Write the policy's text before publishing it" }
        val previous = versionRepository.findByPolicy(policy.id!!).lastOrNull { it.status.published }
        val effective = version.effectiveFrom ?: today()
        if (previous != null && effective.isBefore(previous.effectiveFrom)) {
            throw IllegalArgumentException("The change can't take effect before ${previous.effectiveFrom}, when the current text does")
        }
    }

    // ---- Reads ----

    fun get(policyId: Long): Policy = policyRepository.findById(policyId) ?: throw NoSuchElementException("Policy $policyId not found")

    fun getVersion(versionId: Long): PolicyVersion =
        versionRepository.findById(versionId) ?: throw NoSuchElementException("Policy version $versionId not found")

    // What the viewer may see: published versions to everyone, drafts and pending versions to drafters.
    // A policy that was never published doesn't exist for other viewers.
    fun details(policyId: Long, viewer: PolicyActor): PolicyDetails {
        val policy = get(policyId)
        if (policy.status == PolicyStatus.DRAFT && !viewer.canDraft) throw NoSuchElementException("Policy $policyId not found")
        val versions = versionRepository.findByPolicy(policyId).filter { it.status.published || viewer.canDraft }
        return PolicyDetails(policy, categoryService.get(policy.categoryId), versions, today())
    }

    fun list(societyId: Long, filter: PolicyFilter, categoryId: Long?, query: String?, viewer: PolicyActor): List<PolicyDetails> {
        if ((filter == PolicyFilter.DRAFTS || filter == PolicyFilter.AWAITING_APPROVAL) && !viewer.canDraft) {
            throw AccessDeniedException("Only the committee sees drafts")
        }
        val categories = categoryService.list(societyId).associateBy { it.id!! }
        val words = query?.trim()?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        val policies = policyRepository.findBySociety(societyId)
            .filter { categoryId == null || it.categoryId == categoryId }
            .filter { p -> words.all { p.title.lowercase().contains(it) } }
        val versions = versionRepository.findByPolicies(policies.map { it.id!! }).groupBy { it.policyId }
        val today = today()
        return policies
            .map { p ->
                val visible = versions[p.id].orEmpty().filter { it.status.published || viewer.canDraft }
                PolicyDetails(p, categories.getValue(p.categoryId), visible, today)
            }
            .filter { d ->
                when (filter) {
                    PolicyFilter.PUBLISHED -> d.policy.status == PolicyStatus.ACTIVE
                    PolicyFilter.ARCHIVED -> d.policy.status == PolicyStatus.ARCHIVED
                    PolicyFilter.DRAFTS -> d.open?.status == VersionStatus.DRAFT
                    PolicyFilter.AWAITING_APPROVAL -> d.open?.status == VersionStatus.PENDING
                }
            }
            .sortedWith(
                compareBy<PolicyDetails>({ it.category.position }, { it.category.id }, { it.policy.title.lowercase() })
            )
    }

    // The attachment of a version the viewer may see.
    fun attachment(versionId: Long, viewer: PolicyActor): Pair<PolicyVersion, Photo> {
        val version = getVersion(versionId)
        if (!version.status.published && !viewer.canDraft) throw NoSuchElementException("Policy version $versionId not found")
        val key = version.attachmentKey ?: throw NoSuchElementException("The version has no attachment")
        val file = storage.load(key) ?: throw NoSuchElementException("The attachment is missing")
        return version to file
    }

    fun societyOfVersion(versionId: Long): Long = get(getVersion(versionId).policyId).societyId

    fun names(userIds: Collection<Long>): Map<Long, String> =
        if (userIds.isEmpty()) emptyMap() else userRepository.findAllById(userIds.toSet()).associate { it.id!! to it.name }

    // ---- Helpers ----

    private fun approvalRequired(societyId: Long) = settings.boolean(societyId, SocietySettingKeys.POLICY_APPROVAL_REQUIRED)

    private fun lock(policyId: Long): Policy = policyRepository.lock(policyId) ?: throw NoSuchElementException("Policy $policyId not found")

    // The policy is locked first, so two changes to its versions never interleave.
    private fun lockVersion(versionId: Long): Pair<Policy, PolicyVersion> {
        val policy = lock(getVersion(versionId).policyId)
        return policy to getVersion(versionId)
    }

    private fun usableCategory(societyId: Long, categoryId: Long): PolicyCategory {
        val category = categoryService.list(societyId).firstOrNull { it.id == categoryId }
            ?: throw IllegalArgumentException("Category $categoryId is not one of this society's")
        require(!category.hidden) { "The category ${category.name} is hidden" }
        return category
    }

    private fun requireApprover(actor: PolicyActor) {
        if (!actor.canApprove) throw AccessDeniedException("Only the chairman, secretary or an admin may do this")
    }

    private fun requireAuthor(version: PolicyVersion, actor: PolicyActor, message: String) {
        if (version.createdBy != actor.userId) throw AccessDeniedException(message)
    }

    private fun requireEditable(version: PolicyVersion, actor: PolicyActor) {
        when (version.status) {
            VersionStatus.DRAFT -> Unit
            VersionStatus.PENDING -> throw ConflictException("The version is waiting for approval; withdraw it to change it")
            else -> throw ConflictException("A published version can't be changed; start a new version")
        }
        if (version.createdBy != actor.userId) requireApprover(actor)
    }

    private fun requireNotArchived(policy: Policy) {
        if (policy.status == PolicyStatus.ARCHIVED) throw ConflictException("Restore the policy before publishing a change")
    }

    private fun nameOf(userId: Long) = userRepository.findById(userId)?.name ?: "Someone"

    private fun cleanTitle(title: String): String {
        val clean = title.trim()
        require(clean.isNotEmpty()) { "Title must not be blank" }
        require(clean.length <= 200) { "Title can be at most 200 characters" }
        return clean
    }

    private fun cleanBody(body: String): String {
        val clean = body.trim()
        require(clean.length <= 50_000) { "The text can be at most 50,000 characters" }
        return clean
    }

    private fun cleanSummary(summary: String?): String? {
        val clean = summary?.trim()?.ifEmpty { null } ?: return null
        require(clean.length <= 500) { "The change summary can be at most 500 characters" }
        return clean
    }

    // In the same transaction: the store is the database today, so a rollback keeps the file too.
    private fun deleteFiles(keys: List<String>) = keys.forEach(storage::delete)
}
