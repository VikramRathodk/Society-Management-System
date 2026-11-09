package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.common.storage.DocumentFormat
import org.devvikram.societymanagement.common.storage.Photo
import org.devvikram.societymanagement.common.storage.PhotoStorage
import org.devvikram.societymanagement.finance.model.AuditAction
import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.model.DocumentType
import org.devvikram.societymanagement.finance.model.FinanceDocument
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.repository.DocumentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Supporting documents of a voucher: bills, receipts, approvals, bank proofs (JPEG/PNG/WebP/PDF, 5 MB).
// They can be added while the voucher is waiting or in the books (an auditor may ask for a bill later),
// but removed only while it is still waiting: once posted, its evidence stays.
@Service
class DocumentService(
    private val documentRepository: DocumentRepository,
    private val voucherService: VoucherService,
    private val storage: PhotoStorage,
    private val audit: FinanceAudit,
) {
    fun get(id: Long): FinanceDocument = documentRepository.findById(id) ?: throw NoSuchElementException("Document $id not found")

    fun forVoucher(voucherId: Long): List<FinanceDocument> = documentRepository.findByVoucherId(voucherId)

    fun forVouchers(voucherIds: Collection<Long>): List<FinanceDocument> = documentRepository.findByVoucherIds(voucherIds)

    fun societyOf(document: FinanceDocument): Long = voucherService.get(document.voucherId).societyId

    fun load(document: FinanceDocument): Photo =
        storage.load(document.storageKey) ?: throw NoSuchElementException("Document ${document.id} has no file")

    @Transactional
    fun add(voucherId: Long, data: ByteArray, filename: String?, type: DocumentType, userId: Long): Long {
        val voucher = voucherService.get(voucherId)
        if (voucher.status == VoucherStatus.REJECTED) throw ConflictException("${voucher.number} was rejected; attach documents to a new entry")
        require(data.isNotEmpty()) { "The file is empty" }
        require(data.size <= DocumentFormat.MAX_BYTES) { "Files can be at most 5 MB" }
        val contentType = DocumentFormat.detect(data) ?: throw IllegalArgumentException("Attach a JPEG, PNG, WebP or PDF file")
        val name = filename?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.take(255)?.ifEmpty { null }
        val document = documentRepository.save(
            FinanceDocument(voucherId, type, storage.save(data, contentType), name, contentType, data.size, userId)
        )
        audit.record(voucher.societyId, AuditEntityType.DOCUMENT, document.id, AuditAction.CREATE, userId,
            newValues = mapOf("voucher" to voucher.number, "type" to type, "filename" to name, "size" to data.size))
        return document.id!!
    }

    @Transactional
    fun remove(id: Long, userId: Long) {
        val document = get(id)
        val voucher = voucherService.get(document.voucherId)
        if (voucher.status != VoucherStatus.PENDING_APPROVAL) {
            throw ConflictException("Documents stay once ${voucher.number} is in the books")
        }
        documentRepository.delete(document)
        storage.delete(document.storageKey)
        audit.record(voucher.societyId, AuditEntityType.DOCUMENT, id, AuditAction.DELETE, userId,
            oldValues = mapOf("voucher" to voucher.number, "type" to document.documentType, "filename" to document.filename))
    }
}
