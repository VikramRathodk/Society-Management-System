package org.devvikram.societymanagement.policy.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

// A heading on the society's rules page (Parking, Pets, …). Hidden ones are not offered for new policies.
@Entity
@Table(name = "policy_category")
class PolicyCategory(
    @Column(name = "society_id", nullable = false)
    var societyId: Long,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var position: Int,

    @Column(nullable = false)
    var hidden: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

// DRAFT until its first version is published; ARCHIVED takes it out of force without losing its history.
enum class PolicyStatus { DRAFT, ACTIVE, ARCHIVED }

// One rule or policy of a society. Its text lives in versions; title, category and whether residents must
// acknowledge it belong to the policy itself.
@Entity
@Table(name = "policy")
class Policy(
    @Column(name = "society_id", nullable = false)
    var societyId: Long,

    @Column(name = "category_id", nullable = false)
    var categoryId: Long,

    @Column(nullable = false)
    var title: String,

    @Column(name = "requires_acknowledgement", nullable = false)
    var requiresAcknowledgement: Boolean,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PolicyStatus = PolicyStatus.DRAFT

    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @Column(name = "updated_by")
    var updatedBy: Long? = null

    @Column(name = "archived_at")
    var archivedAt: Instant? = null

    @Column(name = "archived_by")
    var archivedBy: Long? = null
}

// DRAFT → PENDING → PUBLISHED; sent back or withdrawn to DRAFT; SUPERSEDED once a later version is published.
enum class VersionStatus {
    DRAFT, PENDING, PUBLISHED, SUPERSEDED;

    val open: Boolean get() = this == DRAFT || this == PENDING
    val published: Boolean get() = this == PUBLISHED || this == SUPERSEDED
}

// One text of a policy. Editable only while DRAFT; never changed once published.
@Entity
@Table(name = "policy_version")
class PolicyVersion(
    @Column(name = "policy_id", nullable = false)
    var policyId: Long,

    @Column(nullable = false)
    var number: Int,

    @Column(nullable = false)
    var body: String,

    @Column(name = "change_summary")
    var changeSummary: String?,

    // Null on a draft: the day it is published.
    @Column(name = "effective_from")
    var effectiveFrom: LocalDate?,

    @Column(name = "material_change", nullable = false)
    var materialChange: Boolean,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: VersionStatus = VersionStatus.DRAFT

    @Column(name = "attachment_key")
    var attachmentKey: String? = null

    @Column(name = "attachment_name")
    var attachmentName: String? = null

    @Column(name = "attachment_type")
    var attachmentType: String? = null

    @Column(name = "attachment_size")
    var attachmentSize: Int? = null

    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @Column(name = "submitted_at")
    var submittedAt: Instant? = null

    @Column(name = "reviewed_at")
    var reviewedAt: Instant? = null

    @Column(name = "reviewed_by")
    var reviewedBy: Long? = null

    // Why it was sent back (the latest time).
    @Column(name = "review_reason")
    var reviewReason: String? = null

    @Column(name = "published_at")
    var publishedAt: Instant? = null

    @Column(name = "published_by")
    var publishedBy: Long? = null
}
