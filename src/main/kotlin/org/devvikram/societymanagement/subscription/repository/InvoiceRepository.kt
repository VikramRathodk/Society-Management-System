package org.devvikram.societymanagement.subscription.repository

import jakarta.persistence.LockModeType
import org.devvikram.societymanagement.subscription.model.PlatformCreditNote
import org.devvikram.societymanagement.subscription.model.PlatformInvoice
import org.devvikram.societymanagement.subscription.model.PlatformPayment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

interface InvoiceRepository {
    fun save(invoice: PlatformInvoice): PlatformInvoice
    fun findById(id: Long): PlatformInvoice?
    fun lock(id: Long): PlatformInvoice?
    // Newest first.
    fun findBySociety(societyId: Long): List<PlatformInvoice>
    fun findAll(): List<PlatformInvoice>
}

interface InvoiceJpaRepository : JpaRepository<PlatformInvoice, Long> {
    fun findBySocietyIdOrderByIssuedOnDescIdDesc(societyId: Long): List<PlatformInvoice>
    fun findAllByOrderByIssuedOnDescIdDesc(): List<PlatformInvoice>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM PlatformInvoice i WHERE i.id = :id")
    fun findLocked(id: Long): PlatformInvoice?
}

@Repository
class InvoiceRepositoryImpl(private val jpa: InvoiceJpaRepository) : InvoiceRepository {
    override fun save(invoice: PlatformInvoice): PlatformInvoice = jpa.saveAndFlush(invoice)
    override fun findById(id: Long): PlatformInvoice? = jpa.findById(id).orElse(null)
    override fun lock(id: Long): PlatformInvoice? = jpa.findLocked(id)
    override fun findBySociety(societyId: Long): List<PlatformInvoice> = jpa.findBySocietyIdOrderByIssuedOnDescIdDesc(societyId)
    override fun findAll(): List<PlatformInvoice> = jpa.findAllByOrderByIssuedOnDescIdDesc()
}

interface PaymentRepository {
    fun save(payment: PlatformPayment): PlatformPayment
    fun findByInvoice(invoiceId: Long): List<PlatformPayment>
    fun findByGatewayId(gatewayPaymentId: String): PlatformPayment?
}

interface PaymentJpaRepository : JpaRepository<PlatformPayment, Long> {
    fun findByInvoiceIdOrderByIdAsc(invoiceId: Long): List<PlatformPayment>
    fun findByGatewayPaymentId(gatewayPaymentId: String): PlatformPayment?
}

@Repository
class PaymentRepositoryImpl(private val jpa: PaymentJpaRepository) : PaymentRepository {
    override fun save(payment: PlatformPayment): PlatformPayment = jpa.saveAndFlush(payment)
    override fun findByInvoice(invoiceId: Long): List<PlatformPayment> = jpa.findByInvoiceIdOrderByIdAsc(invoiceId)
    override fun findByGatewayId(gatewayPaymentId: String): PlatformPayment? = jpa.findByGatewayPaymentId(gatewayPaymentId)
}

interface CreditNoteRepository {
    fun save(note: PlatformCreditNote): PlatformCreditNote
    fun findByInvoice(invoiceId: Long): List<PlatformCreditNote>
}

interface CreditNoteJpaRepository : JpaRepository<PlatformCreditNote, Long> {
    fun findByInvoiceIdOrderByIdAsc(invoiceId: Long): List<PlatformCreditNote>
}

@Repository
class CreditNoteRepositoryImpl(private val jpa: CreditNoteJpaRepository) : CreditNoteRepository {
    override fun save(note: PlatformCreditNote): PlatformCreditNote = jpa.saveAndFlush(note)
    override fun findByInvoice(invoiceId: Long): List<PlatformCreditNote> = jpa.findByInvoiceIdOrderByIdAsc(invoiceId)
}

interface PlatformCounter {
    // The next number of this kind in this financial year; safe under concurrency.
    fun next(kind: String, financialYear: String): Int
}

interface PlatformCounterJpaRepository : JpaRepository<PlatformCreditNote, Long> {
    @Query(
        value = "INSERT INTO platform_counter (kind, financial_year, last_number) VALUES (:kind, :year, 1) " +
            "ON CONFLICT (kind, financial_year) DO UPDATE SET last_number = platform_counter.last_number + 1 RETURNING last_number",
        nativeQuery = true,
    )
    fun next(kind: String, year: String): Int
}

@Repository
class PlatformCounterImpl(private val jpa: PlatformCounterJpaRepository) : PlatformCounter {
    override fun next(kind: String, financialYear: String): Int = jpa.next(kind, financialYear)
}
