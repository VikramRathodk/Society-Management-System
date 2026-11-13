package org.devvikram.societymanagement.finance.repository

import org.devvikram.societymanagement.finance.model.BankLine
import org.devvikram.societymanagement.finance.model.BankLineState
import org.devvikram.societymanagement.finance.model.BankStatement
import org.devvikram.societymanagement.finance.model.StatementImport
import org.devvikram.societymanagement.finance.model.StatementMapping
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

// Bank statements: column mappings, statement periods, their imports and lines.
interface BankStatementRepository {
    fun findMapping(accountId: Long): StatementMapping?
    fun saveMapping(mapping: StatementMapping): StatementMapping

    fun save(statement: BankStatement): BankStatement
    fun findById(id: Long): BankStatement?
    // Newest period first.
    fun findBySociety(societyId: Long, accountId: Long?): List<BankStatement>
    fun findLatest(accountId: Long): BankStatement?
    // The account's first statement: where its reconciliation starts.
    fun findFirst(accountId: Long): BankStatement?
    fun delete(statement: BankStatement)

    fun saveImport(statementImport: StatementImport): StatementImport
    fun findImport(id: Long): StatementImport?
    fun findImports(statementId: Long): List<StatementImport>
    fun deleteImport(statementImport: StatementImport)

    fun saveLines(lines: List<BankLine>): List<BankLine>
    // By date, then as the bank listed them.
    fun findLines(statementId: Long, state: BankLineState?): List<BankLine>
    fun existingFingerprints(accountId: Long, fingerprints: Collection<String>): Set<String>
    fun maxLineNo(statementId: Long): Int
    fun countLinesByImport(importId: Long): Long
}

interface StatementMappingJpaRepository : JpaRepository<StatementMapping, Long>

interface BankStatementJpaRepository : JpaRepository<BankStatement, Long> {
    fun findBySocietyIdOrderByPeriodFromDescIdDesc(societyId: Long): List<BankStatement>
    fun findBySocietyIdAndAccountIdOrderByPeriodFromDescIdDesc(societyId: Long, accountId: Long): List<BankStatement>
    fun findFirstByAccountIdOrderByPeriodToDesc(accountId: Long): BankStatement?
    fun findFirstByAccountIdOrderByPeriodFromAsc(accountId: Long): BankStatement?
}

interface StatementImportJpaRepository : JpaRepository<StatementImport, Long> {
    fun findByStatementIdOrderById(statementId: Long): List<StatementImport>
}

interface BankLineJpaRepository : JpaRepository<BankLine, Long> {
    fun findByStatementIdOrderByTxnDateAscLineNoAsc(statementId: Long): List<BankLine>
    fun findByStatementIdAndStateOrderByTxnDateAscLineNoAsc(statementId: Long, state: BankLineState): List<BankLine>
    fun countByImportId(importId: Long): Long
    fun findByVoucherId(voucherId: Long): List<BankLine>

    @Query("select l.fingerprint from BankLine l where l.accountId = :accountId and l.fingerprint in :fingerprints")
    fun fingerprintsIn(accountId: Long, fingerprints: Collection<String>): List<String>

    @Query("select coalesce(max(l.lineNo), 0) from BankLine l where l.statementId = :statementId")
    fun maxLineNo(statementId: Long): Int
}

@Repository
class BankStatementRepositoryImpl(
    private val mappings: StatementMappingJpaRepository,
    private val statements: BankStatementJpaRepository,
    private val imports: StatementImportJpaRepository,
    private val lines: BankLineJpaRepository,
) : BankStatementRepository {
    override fun findMapping(accountId: Long): StatementMapping? = mappings.findById(accountId).orElse(null)

    override fun saveMapping(mapping: StatementMapping): StatementMapping = mappings.save(mapping)

    override fun save(statement: BankStatement): BankStatement = statements.saveAndFlush(statement)

    override fun findById(id: Long): BankStatement? = statements.findById(id).orElse(null)

    override fun findBySociety(societyId: Long, accountId: Long?): List<BankStatement> =
        if (accountId == null) statements.findBySocietyIdOrderByPeriodFromDescIdDesc(societyId)
        else statements.findBySocietyIdAndAccountIdOrderByPeriodFromDescIdDesc(societyId, accountId)

    override fun findLatest(accountId: Long): BankStatement? = statements.findFirstByAccountIdOrderByPeriodToDesc(accountId)

    override fun findFirst(accountId: Long): BankStatement? = statements.findFirstByAccountIdOrderByPeriodFromAsc(accountId)

    override fun delete(statement: BankStatement) {
        statements.delete(statement)
        statements.flush()
    }

    override fun saveImport(statementImport: StatementImport): StatementImport = imports.save(statementImport)

    override fun findImport(id: Long): StatementImport? = imports.findById(id).orElse(null)

    override fun findImports(statementId: Long): List<StatementImport> = imports.findByStatementIdOrderById(statementId)

    override fun deleteImport(statementImport: StatementImport) {
        imports.delete(statementImport)
        imports.flush()
    }

    override fun saveLines(lines: List<BankLine>): List<BankLine> = this.lines.saveAllAndFlush(lines)

    override fun findLines(statementId: Long, state: BankLineState?): List<BankLine> =
        if (state == null) lines.findByStatementIdOrderByTxnDateAscLineNoAsc(statementId)
        else lines.findByStatementIdAndStateOrderByTxnDateAscLineNoAsc(statementId, state)

    override fun existingFingerprints(accountId: Long, fingerprints: Collection<String>): Set<String> =
        if (fingerprints.isEmpty()) emptySet() else fingerprints.chunked(1000).flatMap { lines.fingerprintsIn(accountId, it) }.toSet()

    override fun maxLineNo(statementId: Long): Int = lines.maxLineNo(statementId)

    override fun countLinesByImport(importId: Long): Long = lines.countByImportId(importId)
}
