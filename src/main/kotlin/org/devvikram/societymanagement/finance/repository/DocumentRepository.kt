package org.devvikram.societymanagement.finance.repository

import org.devvikram.societymanagement.finance.model.FinanceDocument
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface DocumentRepository {
    fun save(document: FinanceDocument): FinanceDocument
    fun findById(id: Long): FinanceDocument?
    // Oldest first.
    fun findByVoucherId(voucherId: Long): List<FinanceDocument>
    fun findByVoucherIds(voucherIds: Collection<Long>): List<FinanceDocument>
    fun delete(document: FinanceDocument)
}

interface DocumentJpaRepository : JpaRepository<FinanceDocument, Long> {
    fun findByVoucherIdOrderById(voucherId: Long): List<FinanceDocument>
    fun findByVoucherIdInOrderById(voucherIds: Collection<Long>): List<FinanceDocument>
}

@Repository
class DocumentRepositoryImpl(private val documentJpaRepository: DocumentJpaRepository) : DocumentRepository {
    override fun save(document: FinanceDocument): FinanceDocument = documentJpaRepository.save(document)

    override fun findById(id: Long): FinanceDocument? = documentJpaRepository.findById(id).orElse(null)

    override fun findByVoucherId(voucherId: Long): List<FinanceDocument> = documentJpaRepository.findByVoucherIdOrderById(voucherId)

    override fun findByVoucherIds(voucherIds: Collection<Long>): List<FinanceDocument> =
        if (voucherIds.isEmpty()) emptyList() else documentJpaRepository.findByVoucherIdInOrderById(voucherIds)

    override fun delete(document: FinanceDocument) = documentJpaRepository.delete(document)
}
