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

enum class DocumentType { INVOICE, RECEIPT, APPROVAL, BANK_PROOF, OTHER }

// A supporting document of a voucher. The bytes live in the shared storage under `storageKey`.
@Entity
@Table(name = "finance_document")
class FinanceDocument(
    @Column(name = "voucher_id", nullable = false)
    var voucherId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    var documentType: DocumentType,

    @Column(name = "storage_key", nullable = false)
    var storageKey: String,

    @Column
    var filename: String?,

    @Column(name = "content_type", nullable = false)
    var contentType: String,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Int,

    @Column(name = "uploaded_by", nullable = false)
    var uploadedBy: Long,

    @Column(name = "uploaded_at", nullable = false)
    var uploadedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
