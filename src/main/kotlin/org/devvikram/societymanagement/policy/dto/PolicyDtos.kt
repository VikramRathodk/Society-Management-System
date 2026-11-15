package org.devvikram.societymanagement.policy.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.devvikram.societymanagement.policy.model.PolicyCategory
import org.devvikram.societymanagement.policy.model.PolicyStatus
import org.devvikram.societymanagement.policy.model.PolicyVersion
import org.devvikram.societymanagement.policy.model.VersionStatus
import org.devvikram.societymanagement.policy.service.PolicyDetails
import java.time.Instant
import java.time.LocalDate

data class CreatePolicyRequest(
    @field:NotNull val categoryId: Long?,
    @field:NotBlank val title: String?,
    val requiresAcknowledgement: Boolean = false,
    val body: String? = null,
    val changeSummary: String? = null,
    val effectiveFrom: LocalDate? = null,
)

// Only the fields sent change.
data class UpdatePolicyRequest(
    val categoryId: Long? = null,
    val title: String? = null,
    val requiresAcknowledgement: Boolean? = null,
)

// The whole draft; effectiveFrom null means "the day it is published".
data class SaveDraftRequest(
    val body: String? = null,
    val changeSummary: String? = null,
    val effectiveFrom: LocalDate? = null,
    val materialChange: Boolean = true,
)

data class ReasonRequest(@field:NotBlank val reason: String?)

data class CreateCategoryRequest(@field:NotBlank val name: String?)

data class UpdateCategoryRequest(
    val name: String? = null,
    val position: Int? = null,
    val hidden: Boolean? = null,
)

data class PolicyCategoryResponse(val id: Long, val name: String, val position: Int, val hidden: Boolean) {
    companion object {
        fun from(category: PolicyCategory) = PolicyCategoryResponse(category.id!!, category.name, category.position, category.hidden)
    }
}

data class PersonRef(val id: Long, val name: String?)

data class AttachmentResponse(val name: String?, val contentType: String, val sizeBytes: Int)

// One version in a policy's history (no text).
data class VersionSummaryResponse(
    val id: Long,
    val number: Int,
    val status: VersionStatus,
    val changeSummary: String?,
    val effectiveFrom: LocalDate?,
    val materialChange: Boolean,
    val publishedAt: Instant?,
    val publishedBy: PersonRef?,
    val createdBy: PersonRef,
) {
    companion object {
        fun from(v: PolicyVersion, names: Map<Long, String>) = VersionSummaryResponse(
            id = v.id!!,
            number = v.number,
            status = v.status,
            changeSummary = v.changeSummary,
            effectiveFrom = v.effectiveFrom,
            materialChange = v.materialChange,
            publishedAt = v.publishedAt,
            publishedBy = v.publishedBy?.let { PersonRef(it, names[it]) },
            createdBy = PersonRef(v.createdBy, names[v.createdBy]),
        )
    }
}

data class VersionResponse(
    val id: Long,
    val policyId: Long,
    val number: Int,
    val status: VersionStatus,
    val body: String,
    val changeSummary: String?,
    val effectiveFrom: LocalDate?,
    val materialChange: Boolean,
    val inForce: Boolean,
    val attachment: AttachmentResponse?,
    val createdBy: PersonRef,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val submittedAt: Instant?,
    val reviewedBy: PersonRef?,
    val reviewedAt: Instant?,
    val reviewReason: String?,
    val publishedBy: PersonRef?,
    val publishedAt: Instant?,
) {
    companion object {
        fun from(v: PolicyVersion, inForce: Boolean, names: Map<Long, String>) = VersionResponse(
            id = v.id!!,
            policyId = v.policyId,
            number = v.number,
            status = v.status,
            body = v.body,
            changeSummary = v.changeSummary,
            effectiveFrom = v.effectiveFrom,
            materialChange = v.materialChange,
            inForce = inForce,
            attachment = v.attachmentKey?.let { AttachmentResponse(v.attachmentName, v.attachmentType!!, v.attachmentSize!!) },
            createdBy = PersonRef(v.createdBy, names[v.createdBy]),
            createdAt = v.createdAt,
            updatedAt = v.updatedAt,
            submittedAt = v.submittedAt,
            reviewedBy = v.reviewedBy?.let { PersonRef(it, names[it]) },
            reviewedAt = v.reviewedAt,
            reviewReason = v.reviewReason,
            publishedBy = v.publishedBy?.let { PersonRef(it, names[it]) },
            publishedAt = v.publishedAt,
        )
    }
}

// A row of the rules page or a committee queue.
data class PolicySummaryResponse(
    val id: Long,
    val societyId: Long,
    val title: String,
    val category: PolicyCategoryResponse,
    val status: PolicyStatus,
    val requiresAcknowledgement: Boolean,
    // The latest published version, and the one in force today (they differ while a change is upcoming).
    val currentVersion: Int?,
    val inForceVersion: Int?,
    val effectiveFrom: LocalDate?,
    val takesEffectOn: LocalDate?,
    val publishedAt: Instant?,
    // Only for those who see drafts.
    val openVersion: VersionSummaryResponse?,
    val updatedAt: Instant?,
) {
    companion object {
        fun from(d: PolicyDetails, names: Map<Long, String>): PolicySummaryResponse {
            val current = d.current
            return PolicySummaryResponse(
                id = d.policy.id!!,
                societyId = d.policy.societyId,
                title = d.policy.title,
                category = PolicyCategoryResponse.from(d.category),
                status = d.policy.status,
                requiresAcknowledgement = d.policy.requiresAcknowledgement,
                currentVersion = current?.number,
                inForceVersion = d.inForce?.number,
                effectiveFrom = current?.effectiveFrom,
                takesEffectOn = current?.takeIf { it !== d.inForce }?.effectiveFrom,
                publishedAt = current?.publishedAt,
                openVersion = d.open?.let { VersionSummaryResponse.from(it, names) },
                updatedAt = d.policy.updatedAt ?: d.policy.createdAt,
            )
        }
    }
}

// A policy with the latest published text, the draft (for those who see drafts) and its history.
data class PolicyResponse(
    val summary: PolicySummaryResponse,
    val current: VersionResponse?,
    val open: VersionResponse?,
    val versions: List<VersionSummaryResponse>,
    val createdBy: PersonRef,
    val createdAt: Instant,
    val archivedAt: Instant?,
) {
    companion object {
        fun from(d: PolicyDetails, names: Map<Long, String>) = PolicyResponse(
            summary = PolicySummaryResponse.from(d, names),
            current = d.current?.let { VersionResponse.from(it, it === d.inForce, names) },
            open = d.open?.let { VersionResponse.from(it, false, names) },
            versions = d.versions.reversed().map { VersionSummaryResponse.from(it, names) },
            createdBy = PersonRef(d.policy.createdBy, names[d.policy.createdBy]),
            createdAt = d.policy.createdAt,
            archivedAt = d.policy.archivedAt,
        )
    }
}

// Everyone a response names, so names are looked up once.
fun peopleOf(d: PolicyDetails): Set<Long> =
    (d.versions.flatMap { listOfNotNull(it.createdBy, it.publishedBy, it.reviewedBy) } + d.policy.createdBy).toSet()
