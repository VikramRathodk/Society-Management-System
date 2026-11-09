package org.devvikram.societymanagement.finance.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class AuditEntityType { VOUCHER, ACCOUNT, BANK_ACCOUNT, BOOKS, MAPPING, SETTING, REFUND, DOCUMENT }

enum class AuditAction { CREATE, UPDATE, DELETE, APPROVE, REJECT, VOID, START, LOCK, UNLOCK }

// One recorded change to the books. Insert-only: the database refuses updates and deletes.
@Entity
@Table(name = "finance_audit_log")
class FinanceAuditEntry(
    @Column(name = "society_id", nullable = false, updatable = false)
    var societyId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, updatable = false)
    var entityType: AuditEntityType,

    @Column(name = "entity_id", updatable = false)
    var entityId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    var action: AuditAction,

    // JSON objects of the changed fields.
    @Column(name = "old_values", updatable = false)
    var oldValues: String?,

    @Column(name = "new_values", updatable = false)
    var newValues: String?,

    @Column(updatable = false)
    var reason: String?,

    @Column(name = "performed_by", nullable = false, updatable = false)
    var performedBy: Long,

    @Column(name = "performed_at", nullable = false, updatable = false)
    var performedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
